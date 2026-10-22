package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
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
import vn.viettel.okrkpi.task.application.change.port.ChangeApprovalStarter;
import vn.viettel.okrkpi.task.application.change.port.ChangeRequestNotices;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxChangeRequestNotices;

/**
 * Duyệt đề nghị (CORE-02-22) trên PostgreSQL thật: gia hạn khách quan dời cả hai hạn, chủ quan chỉ
 * dời hạn thực hiện, người gửi tự duyệt 403, thiếu lý do 422, lần gia hạn thứ ba vượt ngưỡng tạo
 * đúng một tiến trình và người giao không quyết định được, tạm dừng rồi tiếp tục qua máy trạng
 * thái.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TaskChangeDecisionApiIT.DecisionTestConfig.class)
class TaskChangeDecisionApiIT extends ContractConformanceTest {

  static final UUID LEAD = TestTaskCallers.DEFAULT_PERSON;
  static final UUID ASSIGNER = UUID.fromString("01927a02-0000-7000-8000-00000000c222");
  static final UUID BOSS = UUID.fromString("01927a02-0000-7000-8000-00000000d222");
  static final int BUCKET = 20301;
  static final String REASON = "Đơn vị phối hợp chưa gửi số liệu tổng hợp quý";
  static final List<UUID> STARTED = new CopyOnWriteArrayList<>();

  static class DecisionTestConfig {

    @Bean
    @Primary
    ChangeRequestNotices decisionTestNotices(
        @Qualifier("taskDataSource") DataSource ds, ObjectMapper json) {
      return new OutboxChangeRequestNotices(
          new OutboxWriter(
              new JdbcTemplate(ds),
              "task",
              (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
              json,
              Clock.systemUtc()),
          json);
    }

    @Bean
    ChangeApprovalStarter decisionTestStarter() {
      return (tenant, taskId, requestId, assignerId, rowVersion, submittedBy) ->
          STARTED.add(requestId);
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
                                    + " updated_by) VALUES (?, 'Q4-2029-VT2', 'Quý IV năm 2029',"
                                    + " 'QUY', 'NHA_NUOC', DATE '2029-10-01', DATE '2029-12-31',"
                                    + " ?, 'MO', ?, ?)")
                            .params(period, BUCKET, LEAD, LEAD)
                            .update()));
    inTask(
        jdbc -> {
          for (String table :
              List.of(
                  "task",
                  "task_assignee",
                  "task_state_log",
                  "task_blocker",
                  "task_change_request")) {
            jdbc.sql("SELECT task.fn_create_period_partitions(?::regclass, ?)")
                .params("task." + table, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
  }

  @AfterEach
  void resetCaller() {
    TestTaskCallers.reset();
  }

  @Test
  void giaHanKhachQuanChuQuanVaVuotNguong() throws Exception {
    UUID task = task();
    UUID first = submitted(task, extension("2029-12-07", REASON));
    TestTaskCallers.current = LEAD_ACTOR;
    assertThat(perform(decide(task, first, true, "KHACH_QUAN", REASON)).getResponse().getStatus())
        .isEqualTo(403);
    TestTaskCallers.current = ASSIGNER_ACTOR;
    assertThat(perform(decide(task, first, true, "KHACH_QUAN", " ")).getResponse().getStatus())
        .isEqualTo(422);
    MvcResult ok =
        perform(decide(task, first, true, "KHACH_QUAN", "Đồng ý, nguyên nhân khách quan"));
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(ok).get("dueDate").asText()).isEqualTo("2029-12-07");
    assertThat(body(ok).get("scoreDueDate").asText()).isEqualTo("2029-12-07");
    assertThat(body(ok).get("extensionsUsed").asInt()).isEqualTo(1);

    TestTaskCallers.reset();
    UUID second = submitted(task, extension("2029-12-10", REASON));
    TestTaskCallers.current = ASSIGNER_ACTOR;
    MvcResult subjective = perform(decide(task, second, true, "CHU_QUAN", "Chậm do chủ quan"));
    assertThat(body(subjective).get("dueDate").asText()).isEqualTo("2029-12-10");
    assertThat(body(subjective).get("scoreDueDate").asText()).isEqualTo("2029-12-07");

    TestTaskCallers.reset();
    UUID third = submitted(task, extension("2029-12-11", REASON));
    assertThat(STARTED).containsExactly(third);
    TestTaskCallers.current = new TaskActor(ASSIGNER, Set.of("manager", "leader"));
    assertThat(perform(decide(task, third, true, "KHACH_QUAN", "Đồng ý")).getResponse().getStatus())
        .isEqualTo(403);
    TestTaskCallers.current = new TaskActor(BOSS, Set.of("leader"));
    String key = UUID.randomUUID().toString();
    MvcResult rejected =
        perform(
            decide(task, third, false, "CHU_QUAN", "Không đủ căn cứ")
                .header("Idempotency-Key", key));
    assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
    assertThat(
            perform(decide(task, third, false, "CHU_QUAN", "Không đủ căn cứ"))
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(STARTED).hasSize(1);
  }

  @Test
  void tamDungRoiTiepTuc() throws Exception {
    UUID task = task();
    Map<String, Object> pause = new HashMap<>();
    pause.put("type", "TAM_DUNG");
    pause.put("causeClaimed", "KHACH_QUAN");
    pause.put("reason", REASON);
    UUID request = submitted(task, pause);
    TestTaskCallers.current = ASSIGNER_ACTOR;
    MvcResult paused = perform(decide(task, request, true, "KHACH_QUAN", "Đồng ý tạm dừng"));
    assertThat(paused.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(paused).get("status").asText()).isEqualTo("TAM_DUNG");
    String etag = paused.getResponse().getHeader("ETag");
    MvcResult resumed =
        perform(
            post("/api/v1/tasks/" + task + "/transitions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transition\":\"TIEP_TUC\"}"));
    assertThat(resumed.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(resumed).get("status").asText()).isEqualTo("DANG_THUC_HIEN");
  }

  static final TaskActor LEAD_ACTOR = new TaskActor(LEAD, Set.of("manager"));
  static final TaskActor ASSIGNER_ACTOR = new TaskActor(ASSIGNER, Set.of("manager"));

  private UUID submitted(UUID task, Map<String, Object> body) throws Exception {
    MvcResult r = perform(submit(task, body));
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    for (JsonNode c : body(r).get("changeRequests")) {
      if ("CHO_DUYET".equals(c.get("status").asText())) {
        return UUID.fromString(c.get("id").asText());
      }
    }
    throw new AssertionError("không thấy đề nghị chờ duyệt");
  }

  private MockHttpServletRequestBuilder decide(
      UUID task, UUID request, boolean approve, String cause, String note) throws Exception {
    Map<String, Object> m = new HashMap<>();
    m.put("approve", approve);
    m.put("causeDecided", cause);
    m.put("note", note);
    return post("/api/v1/tasks/" + task + "/change-requests/" + request + "/decision")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(m));
  }

  private Map<String, Object> extension(String due, String reason) {
    Map<String, Object> m = new HashMap<>();
    m.put("type", "GIA_HAN");
    m.put("newDueDate", due);
    m.put("causeClaimed", "KHACH_QUAN");
    m.put("reason", reason);
    return m;
  }

  private MockHttpServletRequestBuilder submit(UUID task, Map<String, Object> body)
      throws Exception {
    return post("/api/v1/tasks/" + task + "/change-requests")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
  }

  private UUID task() throws Exception {
    UUID id = UUID.randomUUID();
    inTask(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket,"
                      + " code, title, task_group, source, assigner_id, due_date, unit,"
                      + " dup_fingerprint, state_machine_version, status)"
                      + " VALUES (?, ?, ?, ?, ?, ?, 'Báo cáo chuyên đề', 'THUONG_XUYEN',"
                      + " 'KE_HOACH', ?, DATE '2029-11-30', 'báo cáo', ?, 'mac-dinh@2.1.0',"
                      + " 'DANG_THUC_HIEN')")
              .params(
                  id,
                  ASSIGNER,
                  ASSIGNER,
                  period,
                  BUCKET,
                  "NV-2029-" + id.toString().substring(0, 8),
                  ASSIGNER,
                  id.toString().getBytes(StandardCharsets.UTF_8))
              .update();
          jdbc.sql(
                  "INSERT INTO task.task_assignee (id, created_by, updated_by, period_id,"
                      + " period_bucket, task_id, assignee_type, person_id, role, valid_from)"
                      + " VALUES (?, ?, ?, ?, ?, ?, 'CA_NHAN', ?, 'CHU_TRI', DATE '2029-10-01')")
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

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(
        tenant,
        () ->
            mvc.perform(request.principal(TestTaskCallers.current.personId()::toString))
                .andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
