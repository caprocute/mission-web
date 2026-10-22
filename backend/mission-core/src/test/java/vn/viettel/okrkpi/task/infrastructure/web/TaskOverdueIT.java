package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.overdue.OverdueSweeper;
import vn.viettel.okrkpi.task.application.overdue.port.TaskReminderNotices;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxTaskReminderNotices;
import vn.viettel.okrkpi.task.infrastructure.scheduling.OverdueJob;

/**
 * Cờ quá hạn (CORE-02-23) trên PostgreSQL thật: lượt quét đầu đặt cờ và ghi đúng một EVT-21 cho
 * nhiệm vụ đã qua hạn, lượt thứ hai không ghi trùng, nhiệm vụ tạm dừng và chưa tới hạn không bị đặt
 * cờ, cờ không làm tăng {@code row_version}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TaskOverdueIT.OverdueTestConfig.class)
class TaskOverdueIT extends ContractConformanceTest {

  static final UUID LEAD = TestTaskCallers.DEFAULT_PERSON;
  static final UUID ASSIGNER = UUID.fromString("01927a02-0000-7000-8000-00000000c223");
  static final int BUCKET = 20294;
  static final LocalDate DUE = LocalDate.of(2029, 9, 14);

  static class OverdueTestConfig {

    @Bean
    @Primary
    TaskReminderNotices overdueTestNotices(
        @Qualifier("taskDataSource") DataSource ds, ObjectMapper json) {
      return new OutboxTaskReminderNotices(
          new OutboxWriter(
              new JdbcTemplate(ds),
              "task",
              (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
              json,
              Clock.systemUtc()),
          json);
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private OverdueSweeper sweeper;
  @Autowired private OverdueJob job;

  @Autowired
  @Qualifier("taskDataSource") private DataSource taskDataSource;

  @Autowired
  @Qualifier("taskTransactionManager") private PlatformTransactionManager taskTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.TASKS;
  }

  @BeforeAll
  void seed() throws Exception {
    TenantContext.call(
        tenant,
        () ->
            new TransactionTemplate(configTm)
                .execute(
                    s ->
                        JdbcClient.create(configDataSource)
                            .sql(
                                "INSERT INTO config.period (id, code, name, kind, flow,"
                                    + " start_date, end_date, period_bucket, status, created_by,"
                                    + " updated_by) VALUES (?, 'Q3-2029-QH', 'Quý III năm 2029',"
                                    + " 'QUY', 'NHA_NUOC', DATE '2029-07-01', DATE '2029-09-30',"
                                    + " ?, 'MO', ?, ?)")
                            .params(period, BUCKET, LEAD, LEAD)
                            .update()));
    inTask(
        jdbc -> {
          for (String table : List.of("task", "task_assignee", "task_state_log")) {
            jdbc.sql("SELECT task.fn_create_period_partitions(?::regclass, ?)")
                .params("task." + table, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
  }

  @Test
  void quetHaiLanChiMotNhacQuaHan() throws Exception {
    UUID late = task("DANG_THUC_HIEN", DUE);
    UUID paused = task("TAM_DUNG", DUE);
    UUID notYet = task("DA_GIAO", DUE.plusDays(30));
    LocalDate today = DUE.plusDays(7);

    OverdueSweeper.Outcome first = TenantContext.call(tenant, () -> sweeper.sweep(tenant, today));
    assertThat(first.flagged()).isEqualTo(1);
    assertThat(overdue(late)).isTrue();
    assertThat(overdue(paused)).isFalse();
    assertThat(overdue(notYet)).isFalse();
    assertThat(events(late)).isEqualTo(1);
    assertThat(version(late)).isZero();

    OverdueSweeper.Outcome second = TenantContext.call(tenant, () -> sweeper.sweep(tenant, today));
    assertThat(second).isEqualTo(new OverdueSweeper.Outcome(0, 0));
    assertThat(events(late)).isEqualTo(1);

    OverdueSweeper.Outcome back = TenantContext.call(tenant, () -> sweeper.sweep(tenant, DUE));
    assertThat(back.cleared()).isEqualTo(1);
    assertThat(overdue(late)).isFalse();
    assertThat(job).isNotNull();
  }

  private boolean overdue(UUID id) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql("SELECT is_overdue FROM task.task WHERE id = ?")
                .param(id)
                .query(Boolean.class)
                .single());
  }

  private long version(UUID id) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql("SELECT row_version FROM task.task WHERE id = ?")
                .param(id)
                .query(Long.class)
                .single());
  }

  private long events(UUID id) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql("SELECT count(*) FROM task.outbox_event WHERE aggregate_id = ?")
                .param(id)
                .query(Long.class)
                .single());
  }

  private UUID task(String status, LocalDate due) throws Exception {
    UUID id = UUID.randomUUID();
    inTask(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket,"
                      + " code, title, task_group, source, assigner_id, due_date, unit,"
                      + " dup_fingerprint, state_machine_version, status)"
                      + " VALUES (?, ?, ?, ?, ?, ?, 'Báo cáo chuyên đề', 'THUONG_XUYEN',"
                      + " 'KE_HOACH', ?, ?, 'báo cáo', ?, 'mac-dinh@2.1.0', ?)")
              .params(
                  id,
                  ASSIGNER,
                  ASSIGNER,
                  period,
                  BUCKET,
                  "NV-2029-" + id.toString().substring(0, 8),
                  ASSIGNER,
                  due,
                  id.toString().getBytes(StandardCharsets.UTF_8),
                  status)
              .update();
          jdbc.sql(
                  "INSERT INTO task.task_assignee (id, created_by, updated_by, period_id,"
                      + " period_bucket, task_id, assignee_type, person_id, role, valid_from)"
                      + " VALUES (?, ?, ?, ?, ?, ?, 'CA_NHAN', ?, 'CHU_TRI', DATE '2029-07-01')")
              .params(UUID.randomUUID(), ASSIGNER, ASSIGNER, period, BUCKET, id, LEAD)
              .update();
          return null;
        });
    return id;
  }

  private <T> T inTask(Function<JdbcClient, T> work) throws Exception {
    JdbcClient jdbc = JdbcClient.create(taskDataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(taskTm).execute(s -> work.apply(jdbc)));
  }
}
