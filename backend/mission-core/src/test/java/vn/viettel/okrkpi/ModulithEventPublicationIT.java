package vn.viettel.okrkpi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.okr.infrastructure.listener.OkrPeriodOpenedListener;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;

/**
 * Kiểm thử tích hợp Spring Modulith Event Publication Registry trên lược đồ {@code
 * modulith.event_publication} (CORE-00-02):
 *
 * <ul>
 *   <li>Sự kiện nội bộ {@code PeriodOpenedEvent} phát từ {@code config} được lưu vào {@code
 *       modulith.event_publication} cùng giao dịch, chuyển giao tới
 *       {@code @ApplicationModuleListener} bên {@code okr} và đánh dấu {@code completion_date !=
 *       null} khi hoàn tất.
 *   <li>Khi listener thất bại tạm thời, bản ghi vẫn còn {@code completion_date IS NULL} trong bảng
 *       {@code modulith.event_publication} và được phát lại thành công qua {@link
 *       IncompleteEventPublications#resubmitIncompletePublications} (tương đương cơ chế {@code
 *       republish-outstanding-events-on-restart: true}).
 * </ul>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class ModulithEventPublicationIT {

  private static final TenantId TENANT_ID =
      TenantId.of(UUID.fromString("01928f3e-1111-7000-8000-000000000011"));

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private PeriodConfigService periodConfigService;

  @Autowired private OkrPeriodOpenedListener okrPeriodOpenedListener;

  @Autowired private IncompleteEventPublications incompleteEventPublications;

  @Autowired
  @Qualifier("taskDataSource") private DataSource taskDataSource;

  @Autowired private TenantProperties tenantProperties;

  @BeforeEach
  void setUp() {
    okrPeriodOpenedListener.reset();
  }

  @Test
  void publishesEventToModulithTableAndMarksCompletedWhenListenerSucceeds() throws Exception {
    vn.viettel.okrkpi.shared.kernel.PeriodId periodId =
        vn.viettel.okrkpi.shared.kernel.PeriodId.of(UUID.randomUUID());
    PeriodSnapshot snapshot =
        new PeriodSnapshot(
            periodId,
            TENANT_ID,
            "2026-Q4",
            "Kỳ đánh giá Quý 4/2026",
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 12, 31),
            PeriodStatus.CHUA_MO);

    TenantContext.run(TENANT_ID, () -> periodConfigService.openPeriod(snapshot));

    awaitCondition(
        Duration.ofSeconds(5),
        () ->
            okrPeriodOpenedListener.hasHandled(periodId)
                && countCompletedPublicationsForPeriod(periodId) >= 1);

    assertThat(okrPeriodOpenedListener.hasHandled(periodId)).isTrue();
    assertThat(countCompletedPublicationsForPeriod(periodId)).isGreaterThanOrEqualTo(1);
  }

  @Test
  void retainsIncompletePublicationWhenListenerFailsAndResubmitsSuccessfully() throws Exception {
    vn.viettel.okrkpi.shared.kernel.PeriodId periodId =
        vn.viettel.okrkpi.shared.kernel.PeriodId.of(UUID.randomUUID());
    PeriodSnapshot snapshot =
        new PeriodSnapshot(
            periodId,
            TENANT_ID,
            "2027-Q1",
            "Kỳ đánh giá Quý 1/2027",
            LocalDate.of(2027, 1, 1),
            LocalDate.of(2027, 3, 31),
            PeriodStatus.CHUA_MO);

    okrPeriodOpenedListener.setFailNextExecution(true);
    TenantContext.run(TENANT_ID, () -> periodConfigService.openPeriod(snapshot));

    awaitCondition(
        Duration.ofSeconds(5), () -> countIncompletePublicationsForPeriod(periodId) == 1);
    assertThat(okrPeriodOpenedListener.hasHandled(periodId)).isFalse();

    // Khôi phục listener và kích hoạt phát lại các sự kiện chưa hoàn tất (giống khi khởi động lại)
    okrPeriodOpenedListener.setFailNextExecution(false);
    TenantContext.run(
        TENANT_ID, () -> incompleteEventPublications.resubmitIncompletePublications(pub -> true));

    awaitCondition(
        Duration.ofSeconds(5),
        () ->
            okrPeriodOpenedListener.hasHandled(periodId)
                && countCompletedPublicationsForPeriod(periodId) == 1);

    assertThat(okrPeriodOpenedListener.hasHandled(periodId)).isTrue();
    assertThat(countIncompletePublicationsForPeriod(periodId)).isZero();
    assertThat(countCompletedPublicationsForPeriod(periodId)).isEqualTo(1);
  }

  private int countIncompletePublicationsForPeriod(
      vn.viettel.okrkpi.shared.kernel.PeriodId periodId) {
    return queryInTenantTransaction(
        "SELECT count(*) FROM modulith.event_publication"
            + " WHERE serialized_event LIKE ? AND completion_date IS NULL",
        "%" + periodId.value() + "%");
  }

  private int countCompletedPublicationsForPeriod(
      vn.viettel.okrkpi.shared.kernel.PeriodId periodId) {
    return queryInTenantTransaction(
        "SELECT count(*) FROM modulith.event_publication"
            + " WHERE serialized_event LIKE ? AND completion_date IS NOT NULL",
        "%" + periodId.value() + "%");
  }

  private int queryInTenantTransaction(String sql, String likeParam) {
    TransactionTemplate tx =
        new TransactionTemplate(
            new TenantDataSourceTransactionManager(taskDataSource, tenantProperties));
    JdbcTemplate jdbc = new JdbcTemplate(taskDataSource);
    try {
      return TenantContext.call(
          TENANT_ID,
          () ->
              tx.execute(
                  status -> {
                    Integer count = jdbc.queryForObject(sql, Integer.class, likeParam);
                    return count == null ? 0 : count;
                  }));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void awaitCondition(Duration timeout, BooleanCheck check)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (check.evaluate()) {
        return;
      }
      Thread.sleep(50L);
    }
  }

  @FunctionalInterface
  private interface BooleanCheck {
    boolean evaluate();
  }
}
