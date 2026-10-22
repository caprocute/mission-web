package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.application.NewPeriod;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.domain.PeriodFlow;
import vn.viettel.okrkpi.config.domain.PeriodKind;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.config.infrastructure.listener.PeriodCacheEvictionListener;
import vn.viettel.okrkpi.config.infrastructure.persistence.JdbcPeriodRepository;
import vn.viettel.okrkpi.config.infrastructure.persistence.OutboxPeriodEvents;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-08-03 trên PostgreSQL thật: một lệnh ghi của MOD-02 (controller thử nằm
 * trong gói {@code task}) bị chặn 409 {@code TASK-409-PERIOD-LOCKED} với thân problem đúng {@code
 * common.yaml} khi kỳ đang khoá; sự kiện mở lại (EVT-26) xoá đệm nên ghi lại được; {@code
 * MO_LAI_HAU_KIEM} vẫn bị coi là khoá. Sự kiện được đưa thẳng vào bộ lắng nghe xoá đệm (môi trường
 * kiểm thử chưa có Kafka).
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0", "mission.config.period-opening.enabled=false"})
@AutoConfigureMockMvc(addFilters = false)
class PeriodLockedWriteIT {

  static final UUID ACTOR = UUID.randomUUID();

  /**
   * Lệnh ghi giả của MOD-02: kiểm kỳ rồi trả 204. Lớp lồng có {@code @RestController} được quét vào
   * ngữ cảnh kiểm thử như các cấu hình kiểm thử lồng khác.
   */
  @RestController
  static class TaskWriteProbe {
    private final PeriodApi periods;

    TaskWriteProbe(PeriodApi periods) {
      this.periods = periods;
    }

    @PostMapping("/api/v1/test-task-writes/{periodId}")
    ResponseEntity<Void> write(@PathVariable UUID periodId) {
      periods.assertWritable(TenantContext.require(), PeriodId.of(periodId));
      return ResponseEntity.noContent().build();
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PeriodConfigService periodConfig;
  @Autowired private JdbcPeriodRepository repository;

  @Autowired
  @Qualifier("configDataSource") private javax.sql.DataSource dataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager tm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());

  @Test
  void lockedPeriodRejectsWritesUntilReopened() throws Exception {
    PeriodId period =
        TenantContext.call(
            tenant,
            () ->
                periodConfig
                    .create(
                        tenant,
                        ACTOR,
                        new NewPeriod(
                            "2028-Q1",
                            "Quý I/2028",
                            PeriodKind.QUY,
                            PeriodFlow.CA_HAI,
                            null,
                            LocalDate.parse("2028-01-01"),
                            LocalDate.parse("2028-03-31"),
                            null,
                            null,
                            null))
                    .period()
                    .id());

    assertThat(write(period).getResponse().getStatus()).isEqualTo(204);

    // Một pod khác khoá kỳ; pod này nhận EVT-09 và xoá đệm.
    move(period, PeriodState.DANG_KHOA);
    MvcResult locked = write(period);
    assertThat(locked.getResponse().getStatus()).isEqualTo(409);
    assertThat(locked.getResponse().getContentType()).startsWith("application/problem+json");
    JsonNode p = body(locked);
    assertThat(p.get("type").asText()).isEqualTo("https://okrkpi.vn/problems/period-locked");
    assertThat(p.get("status").asInt()).isEqualTo(409);
    assertThat(p.get("code").asText()).isEqualTo("TASK-409-PERIOD-LOCKED");
    assertThat(p.get("title").asText()).isNotBlank();
    assertThat(p.get("detail").asText())
        .isEqualTo("Kỳ Quý I/2028 đang khoá. Dữ liệu của kỳ không thể thay đổi.");

    // Bù trừ saga: DANG_KHOA → MO, EVT-26 xoá đệm, ghi lại được.
    move(period, PeriodState.MO);
    assertThat(write(period).getResponse().getStatus()).isEqualTo(204);

    // Mở lại hậu kiểm vẫn bị coi là khoá ở CORE-08-03.
    move(period, PeriodState.DANG_KHOA);
    move(period, PeriodState.DA_KHOA);
    move(period, PeriodState.MO_LAI_HAU_KIEM);
    assertThat(write(period).getResponse().getStatus()).isEqualTo(409);
  }

  private MvcResult write(PeriodId period) throws Exception {
    return as(() -> mvc.perform(post("/api/v1/test-task-writes/" + period.value())).andReturn());
  }

  /** Chuyển trạng thái bằng một dịch vụ khác (như pod khác) rồi giao sự kiện cho bộ xoá đệm. */
  private void move(PeriodId period, PeriodState target) {
    OutboxWriter writer =
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "config",
            (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
            json,
            Clock.systemUtc());
    PeriodConfigService otherPod =
        new PeriodConfigService(
            repository, new OutboxPeriodEvents(writer), e -> {}, null, Clock.systemUtc());
    TenantContext.run(
        tenant,
        () ->
            new TransactionTemplate(tm)
                .executeWithoutResult(
                    s ->
                        otherPod.transition(
                            tenant,
                            period,
                            target,
                            UUID.randomUUID(),
                            ACTOR,
                            target == PeriodState.MO ? UUID.randomUUID() : null,
                            target == PeriodState.MO ? "Bù trừ" : null)));
    new PeriodCacheEvictionListener(periodConfig)
        .onMessage(
            new ConsumerRecord<>("evt", 0, 0L, tenant.value() + ":" + period.value(), new byte[0]));
  }

  private <T> T as(Callable<T> work) throws Exception {
    return TenantContext.call(tenant, work);
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
