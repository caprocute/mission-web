package vn.viettel.okrkpi.task.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.api.TaskApi.DeliverableState;
import vn.viettel.okrkpi.task.api.TaskApi.Outcome;
import vn.viettel.okrkpi.task.api.TaskApi.TransitionRequest;
import vn.viettel.okrkpi.task.api.events.TrangThaiNhiemVuDoi;

/**
 * CORE-02-12 trên PostgreSQL thật: {@link TaskApi} đọc và chuyển nhiệm vụ; nhiệm vụ sang {@code
 * HOAN_THANH} phát {@link TrangThaiNhiemVuDoi} với {@code isTerminal = true} tới listener
 * {@code @ApplicationModuleListener}; listener lỗi thì bản ghi {@code modulith.event_publication}
 * còn mở và được phát lại (như khi khởi động lại).
 */
@SpringBootTest(
    classes = {MissionCoreApplication.class, TaskApiEventsIT.Beans.class},
    properties = "mission.outbox.relay.enabled=false")
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskApiEventsIT {

  static final int BUCKET = 20273;
  static final UUID ASSIGNER = UUID.fromString("01927a02-0000-7000-8000-00000000c212");

  /** Listener mẫu theo TL-01 ({@code TaskStatusListener} của MOD-04 viết theo mẫu này). */
  static class SampleListener {
    final Set<UUID> handled = ConcurrentHashMap.newKeySet();
    final AtomicBoolean failNext = new AtomicBoolean();

    @ApplicationModuleListener
    void on(TrangThaiNhiemVuDoi event) {
      if (!event.isTerminal()) {
        return;
      }
      if (failNext.getAndSet(false)) {
        throw new IllegalStateException("Lỗi tạm thời của listener mẫu");
      }
      handled.add(event.taskId());
    }

    // Truy cập qua phương thức: bean là proxy (giao dịch của listener), trường của proxy rỗng.
    public boolean hasHandled(UUID taskId) {
      return handled.contains(taskId);
    }

    public void failNextExecution() {
      failNext.set(true);
    }
  }

  /**
   * Nguồn bean không chú thích {@code @TestConfiguration}: {@code MissionCoreApplication} quét
   * thành phần không kèm {@code TypeExcludeFilter} nên cấu hình kiểm thử lồng nhau lọt vào mọi ngữ
   * cảnh. Bean {@code TaskStatusEvents} giả lấy từ cấu hình của {@code TaskTransitionApiIT}.
   */
  static class Beans {
    @Bean
    SampleListener sampleTaskListener() {
      return new SampleListener();
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private TaskApi taskApi;
  @Autowired private SampleListener listener;
  @Autowired private IncompleteEventPublications incomplete;

  @Autowired
  @Qualifier("taskDataSource") private DataSource taskDataSource;

  @Autowired
  @Qualifier("taskTransactionManager") private PlatformTransactionManager taskTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private final UUID lead = UUID.randomUUID();

  @BeforeAll
  void seed() throws Exception {
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
  void docLietKeVaChuyenHoanThanhPhatSuKienKetThuc() throws Exception {
    UUID id = task("CHO_NGHIEM_THU");

    TaskSummary found = TenantContext.call(tenant, () -> taskApi.find(tenant, id)).orElseThrow();
    assertThat(found.status()).isEqualTo("CHO_NGHIEM_THU");
    assertThat(found.leadAssigneeId()).isEqualTo(lead);
    List<TaskSummary> mine =
        TenantContext.call(
            tenant, () -> taskApi.listForPerson(tenant, PeriodId.of(period), lead, null, 50));
    assertThat(mine).extracting(TaskSummary::id).contains(id);

    var out =
        TenantContext.call(
            tenant,
            () ->
                taskApi.requestTransition(
                    tenant,
                    new TransitionRequest(
                        id,
                        TaskApi.Command.APPROVE,
                        ASSIGNER,
                        Set.of("manager"),
                        null,
                        List.of(new DeliverableState(UUID.randomUUID(), "DA_XAC_NHAN")),
                        null)));
    assertThat(out.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(out.task().terminal()).isTrue();
    await(() -> listener.hasHandled(id));
  }

  @Test
  void listenerLoiThiBanGhiConMoVaDuocPhatLai() throws Exception {
    UUID id = task("CHO_NGHIEM_THU");
    listener.failNextExecution();
    TenantContext.call(
        tenant,
        () ->
            taskApi.requestTransition(
                tenant,
                new TransitionRequest(
                    id,
                    TaskApi.Command.APPROVE,
                    ASSIGNER,
                    Set.of("manager"),
                    null,
                    List.of(new DeliverableState(UUID.randomUUID(), "DA_XAC_NHAN")),
                    null)));
    await(() -> openPublications(id) == 1);
    assertThat(listener.hasHandled(id)).isFalse();

    TenantContext.run(tenant, () -> incomplete.resubmitIncompletePublications(p -> true));
    await(() -> listener.hasHandled(id) && openPublications(id) == 0);
  }

  private int openPublications(UUID taskId) {
    try {
      return inTask(
          jdbc ->
              jdbc.sql(
                      "SELECT count(*) FROM modulith.event_publication WHERE completion_date IS"
                          + " NULL AND event_type LIKE '%TrangThaiNhiemVuDoi' AND serialized_event"
                          + " LIKE ?")
                  .param("%" + taskId + "%")
                  .query(Integer.class)
                  .single());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UUID task(String status) throws Exception {
    UUID id = UUID.randomUUID();
    inTask(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket,"
                      + " code, title, task_group, source, assigner_id, acceptor_id, due_date,"
                      + " unit, dup_fingerprint, state_machine_version, status) VALUES (?, ?, ?,"
                      + " ?, ?, ?, 'Báo cáo tổng kết công tác quý', 'THUONG_XUYEN', 'KE_HOACH', ?,"
                      + " ?, DATE '2027-03-20', 'báo cáo', ?, 'mac-dinh@2.1.0', ?)")
              .params(
                  id,
                  ASSIGNER,
                  ASSIGNER,
                  period,
                  BUCKET,
                  "NV-" + id.toString().substring(0, 8),
                  ASSIGNER,
                  ASSIGNER,
                  id.toString().getBytes(StandardCharsets.UTF_8),
                  status)
              .update();
          jdbc.sql(
                  "INSERT INTO task.task_assignee (id, created_by, updated_by, period_id,"
                      + " period_bucket, task_id, assignee_type, person_id, role, valid_from)"
                      + " VALUES (?, ?, ?, ?, ?, ?, 'CA_NHAN', ?, 'CHU_TRI', DATE '2027-01-01')")
              .params(UUID.randomUUID(), ASSIGNER, ASSIGNER, period, BUCKET, id, lead)
              .update();
          return null;
        });
    return id;
  }

  private <T> T inTask(Function<JdbcClient, T> work) throws Exception {
    return TenantContext.call(
        tenant,
        () ->
            new TransactionTemplate(taskTm)
                .execute(s -> work.apply(JdbcClient.create(taskDataSource))));
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    assertThat(condition.getAsBoolean()).as("điều kiện trong 10 giây").isTrue();
  }
}
