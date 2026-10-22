package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskProgressEvents;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxTaskProgressEvents;

/**
 * {@code updateTaskProgress}, {@code updateTaskProgressBatch} (CORE-02-19) trên PostgreSQL thật:
 * hai cập nhật cùng {@code If-Match} thì một bên 409, gửi lại cùng khoá chỉ một dòng và một EVT-05,
 * phối hợp đổi phần trăm 403, giảm không ghi chú 422 MSG-06-01, lần đầu tự nhận việc.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TaskProgressApiIT.TaskProgressTestConfig.class)
class TaskProgressApiIT extends ContractConformanceTest {

  static final UUID LEAD = TestTaskCallers.DEFAULT_PERSON;
  static final UUID COORDINATOR = UUID.fromString("01927a02-0000-7000-8000-00000000c219");
  static final int BUCKET = 20272;

  /**
   * EVT-06 dùng bean {@code testTaskStatusEvents} của {@link TaskTransitionApiIT} (quét chung gói).
   */
  static class TaskProgressTestConfig {

    private static OutboxWriter writer(DataSource ds, ObjectMapper json) {
      return new OutboxWriter(
          new JdbcTemplate(ds),
          "task",
          (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
          json,
          Clock.systemUtc());
    }

    @Bean
    @Primary
    TaskProgressEvents progressTestProgressEvents(
        @Qualifier("taskDataSource") DataSource ds, ObjectMapper json) {
      return new OutboxTaskProgressEvents(writer(ds, json));
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

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
                                    + " updated_by) VALUES (?, 'Q1-2027-TD', 'Quý I năm 2027',"
                                    + " 'QUY', 'NHA_NUOC', DATE '2027-01-01', DATE '2027-03-31',"
                                    + " ?, 'MO', ?, ?)")
                            .params(period, BUCKET, LEAD, LEAD)
                            .update()));
    inTask(
        jdbc -> {
          for (String table : List.of("task", "task_assignee", "task_state_log", "task_progress")) {
            jdbc.sql("SELECT task.fn_create_period_partitions(?::regclass, ?)")
                .params("task." + table, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
  }

  @Test
  void capNhatLanDauTuNhanViecVaGuiLaiKhongTrung() throws Exception {
    UUID id = task("DA_GIAO");
    String key = UUID.randomUUID().toString();
    MvcResult first = perform(progress(id, "\"0\"", key, 40, "Đã xong phần khảo sát"));
    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    JsonNode body = body(first);
    assertThat(body.get("status").asText()).isEqualTo("DANG_THUC_HIEN");
    assertThat(body.get("progress").asInt()).isEqualTo(40);
    assertThat(first.getResponse().getHeader("ETag")).isEqualTo("\"2\"");

    MvcResult replay = perform(progress(id, "\"0\"", key, 40, "Đã xong phần khảo sát"));
    assertThat(replay.getResponse().getStatus()).isEqualTo(200);
    assertThat(count("task.task_progress", id)).isEqualTo(1);
    assertThat(outbox(id))
        .containsExactlyInAnyOrder("task.task.statuschanged.v1", "task.task.progressed.v1");

    // Giảm phần trăm không ghi chú: 422 MSG-06-01.
    MvcResult down = perform(progress(id, "\"2\"", UUID.randomUUID().toString(), 20, " "));
    assertThat(down.getResponse().getStatus()).isEqualTo(422);
    assertThat(down.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("MSG-06-01");
  }

  @Test
  void haiCapNhatCungPhienBanMotBen409() throws Exception {
    UUID id = task("DANG_THUC_HIEN");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Integer> call =
          () ->
              perform(progress(id, "\"0\"", UUID.randomUUID().toString(), 50, ""))
                  .getResponse()
                  .getStatus();
      Future<Integer> a = pool.submit(call);
      Future<Integer> b = pool.submit(call);
      assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("task.task_progress", id)).isEqualTo(1);
  }

  @Test
  void phoiHopChiGhiNhatKyVaHangLoat() throws Exception {
    UUID id = task("DANG_THUC_HIEN");
    TestTaskCallers.current = new TaskActor(COORDINATOR, Set.of("member"));
    try {
      assertThat(
              perform(progress(id, "\"0\"", UUID.randomUUID().toString(), 30, "Đã gửi số liệu"))
                  .getResponse()
                  .getStatus())
          .isEqualTo(403);
      MvcResult log =
          perform(progress(id, "\"0\"", UUID.randomUUID().toString(), 0, "Đã gửi số liệu"));
      assertThat(log.getResponse().getStatus()).isEqualTo(200);
      assertThat(body(log).get("rowVersion").asInt()).isEqualTo(0);
    } finally {
      TestTaskCallers.reset();
    }

    UUID other = task("DANG_THUC_HIEN");
    MvcResult batch =
        perform(
            post("/api/v1/tasks/progress-batch")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "items",
                            List.of(
                                Map.of("taskId", id, "rowVersion", 0, "percent", 60, "note", ""),
                                Map.of(
                                    "taskId",
                                    other,
                                    "rowVersion",
                                    7,
                                    "percent",
                                    60,
                                    "note",
                                    ""))))));
    assertThat(batch.getResponse().getStatus()).isEqualTo(200);
    JsonNode results = body(batch).get("results");
    assertThat(results.get(0).get("status").asInt()).isEqualTo(200);
    assertThat(results.get(0).get("task").get("progress").asInt()).isEqualTo(60);
    assertThat(results.get(1).get("status").asInt()).isEqualTo(409);
    assertThat(results.get(1).get("problem").get("code").asText())
        .isEqualTo("TASK-409-VERSION-CONFLICT");
    assertThat(count("task.task_progress", other)).isZero();
  }

  private UUID task(String status) throws Exception {
    UUID id = UUID.randomUUID();
    inTask(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket,"
                      + " code, title, task_group, source, assigner_id, due_date, unit,"
                      + " dup_fingerprint, state_machine_version, status) VALUES (?, ?, ?, ?, ?,"
                      + " ?, 'Báo cáo tổng kết công tác quý', 'THUONG_XUYEN', 'KE_HOACH', ?,"
                      + " DATE '2027-03-20', 'báo cáo', ?, 'mac-dinh@2.1.0', ?)")
              .params(
                  id,
                  LEAD,
                  LEAD,
                  period,
                  BUCKET,
                  "NV-" + id.toString().substring(0, 8),
                  LEAD,
                  id.toString().getBytes(StandardCharsets.UTF_8),
                  status)
              .update();
          for (Object[] a :
              List.of(new Object[] {LEAD, "CHU_TRI"}, new Object[] {COORDINATOR, "PHOI_HOP"})) {
            jdbc.sql(
                    "INSERT INTO task.task_assignee (id, created_by, updated_by, period_id,"
                        + " period_bucket, task_id, assignee_type, person_id, role, valid_from)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'CA_NHAN', ?, ?, DATE '2027-01-01')")
                .params(UUID.randomUUID(), LEAD, LEAD, period, BUCKET, id, a[0], a[1])
                .update();
          }
          return null;
        });
    return id;
  }

  private MockHttpServletRequestBuilder progress(
      UUID id, String ifMatch, String key, int percent, String note) throws Exception {
    return post("/api/v1/tasks/" + id + "/progress")
        .header("Idempotency-Key", key)
        .header("If-Match", ifMatch)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("percent", percent, "note", note)));
  }

  private long count(String table, UUID taskId) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql("SELECT count(*) FROM " + table + " WHERE task_id = ?")
                .param(taskId)
                .query(Long.class)
                .single());
  }

  private List<String> outbox(UUID id) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql("SELECT topic FROM task.outbox_event WHERE aggregate_id = ?")
                .param(id)
                .query(String.class)
                .list());
  }

  private <T> T inTask(Function<JdbcClient, T> work) throws Exception {
    JdbcClient jdbc = JdbcClient.create(taskDataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(taskTm).execute(s -> work.apply(jdbc)));
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    TaskActor actor = TestTaskCallers.current;
    return TenantContext.call(
        tenant, () -> mvc.perform(request.principal(actor.personId()::toString)).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
