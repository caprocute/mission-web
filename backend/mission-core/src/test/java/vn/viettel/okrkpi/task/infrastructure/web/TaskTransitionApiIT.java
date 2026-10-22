package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskStatusEvents;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxTaskStatusEvents;

/**
 * Kiểm thử tích hợp CORE-02-13 trên PostgreSQL thật: {@code POST /tasks/{id}/transitions} khớp
 * {@code tasks.yaml}; mỗi lần chuyển ghi đúng một dòng {@code task_state_log} và một EVT-06 vào
 * {@code task.outbox_event}; thiếu {@code If-Match} trả 428, sai phiên bản 409, lệnh không có trong
 * bảng 409, sai vai trò 403, thiếu lý do 422.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskTransitionApiIT extends ContractConformanceTest {

  static final UUID ASSIGNER = UUID.fromString("01927a02-0000-7000-8000-00000000a213");
  static final int BUCKET = 20272;
  static volatile TaskActor caller = new TaskActor(ASSIGNER, Set.of("manager"));

  @TestConfiguration
  static class TaskTransitionTestConfig {

    @Bean
    TaskCaller testTaskCaller() {
      return () -> Optional.of(caller);
    }

    @Bean
    @Primary
    TaskStatusEvents testTaskStatusEvents(
        @Qualifier("taskDataSource") DataSource ds, ObjectMapper json) {
      return new OutboxTaskStatusEvents(
          new OutboxWriter(
              new JdbcTemplate(ds),
              "task",
              (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
              json,
              Clock.systemUtc()));
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
                                    + " updated_by) VALUES (?, 'Q1-2027-NV', 'Quý I năm 2027',"
                                    + " 'QUY', 'NHA_NUOC', DATE '2027-01-01', DATE '2027-03-31',"
                                    + " ?, 'MO', ?, ?)")
                            .params(period, BUCKET, ASSIGNER, ASSIGNER)
                            .update()));
    inTask(
        jdbc -> {
          for (String table :
              List.of("task", "task_assignee", "task_state_log", "task_change_request")) {
            jdbc.sql("SELECT task.fn_create_period_partitions(?::regclass, ?)")
                .params("task." + table, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
  }

  @Test
  void chuyenTrangThaiKhopHopDongVaGhiNhatKyOutbox() throws Exception {
    UUID id = task("TAM_DUNG");

    MvcResult missing = perform(transition(id, null, "TIEP_TUC", null));
    assertThat(missing.getResponse().getStatus()).isEqualTo(428);

    MvcResult stale = perform(transition(id, "\"7\"", "TIEP_TUC", null));
    assertThat(stale.getResponse().getStatus()).isEqualTo(409);

    MvcResult ok = perform(transition(id, "\"0\"", "TIEP_TUC", null));
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(ok, "/tasks/{id}/transitions", PathItem.HttpMethod.POST);
    assertThat(ok.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    JsonNode body = body(ok);
    assertThat(body.get("status").asText()).isEqualTo("DANG_THUC_HIEN");
    assertThat(body.get("stateLog")).hasSize(1);

    Map<String, Object> log =
        inTask(
            jdbc ->
                jdbc.sql(
                        "SELECT from_status, to_status, transition, state_machine_version,"
                            + " before_after::text AS ba FROM task.task_state_log WHERE task_id = ?")
                    .param(id)
                    .query()
                    .singleRow());
    assertThat(log)
        .containsEntry("from_status", "TAM_DUNG")
        .containsEntry("to_status", "DANG_THUC_HIEN")
        .containsEntry("transition", "RESUME")
        .containsEntry("state_machine_version", "mac-dinh@2.1.0");
    assertThat(outbox(id)).containsExactly("task.task.statuschanged.v1");

    // Lệnh không có trong bảng: DANG_THUC_HIEN không có TIEP_TUC.
    MvcResult invalid = perform(transition(id, "\"1\"", "TIEP_TUC", null));
    assertThat(invalid.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(invalid).get("code").asText()).isEqualTo("TASK-409-INVALID-TRANSITION");
    assertThat(outbox(id)).hasSize(1);
  }

  @Test
  void vaiTroVaDieuKienBiTuChoi() throws Exception {
    UUID id = task("TAM_DUNG");
    caller = new TaskActor(UUID.randomUUID(), Set.of("member"));
    try {
      assertThat(perform(transition(id, "\"0\"", "TIEP_TUC", null)).getResponse().getStatus())
          .isEqualTo(403);
      // Lãnh đạo khác người giao: guard actor.person_id == task.nguoi_giao_id sai.
      caller = new TaskActor(UUID.randomUUID(), Set.of("manager"));
      MvcResult guard = perform(transition(id, "\"0\"", "TIEP_TUC", null));
      assertThat(guard.getResponse().getStatus()).isEqualTo(422);
      assertThat(guard.getResponse().getContentAsString(StandardCharsets.UTF_8))
          .doesNotContain("nguoi_giao_id");
    } finally {
      caller = new TaskActor(ASSIGNER, Set.of("manager"));
    }
    assertThat(outbox(id)).isEmpty();

    UUID review = task("CHO_NGHIEM_THU");
    MvcResult done = perform(transition(review, "\"0\"", "HOAN_THANH", "Đạt yêu cầu"));
    assertThat(done.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(done).get("status").asText()).isEqualTo("HOAN_THANH");
    Boolean completed =
        inTask(
            jdbc ->
                jdbc.sql("SELECT completed_at IS NOT NULL FROM task.task WHERE id = ?")
                    .param(review)
                    .query(Boolean.class)
                    .single());
    assertThat(completed).isTrue();
  }

  private UUID task(String status) throws Exception {
    UUID id = UUID.randomUUID();
    inTask(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket,"
                        + " code, title, task_group, source, assigner_id, due_date, unit,"
                        + " dup_fingerprint, state_machine_version, status) VALUES (?, ?, ?, ?, ?,"
                        + " ?, 'Báo cáo tổng kết công tác quý', 'THUONG_XUYEN', 'KE_HOACH', ?,"
                        + " DATE '2027-03-20', 'báo cáo', ?, 'mac-dinh@2.1.0', ?)")
                .params(
                    id,
                    ASSIGNER,
                    ASSIGNER,
                    period,
                    BUCKET,
                    "NV-" + id.toString().substring(0, 8),
                    ASSIGNER,
                    id.toString().getBytes(StandardCharsets.UTF_8),
                    status)
                .update());
    return id;
  }

  private List<String> outbox(UUID id) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql("SELECT topic FROM task.outbox_event WHERE aggregate_id = ?")
                .param(id)
                .query(String.class)
                .list());
  }

  private MockHttpServletRequestBuilder transition(
      UUID id, String ifMatch, String transition, String reason) throws Exception {
    MockHttpServletRequestBuilder b =
        post("/api/v1/tasks/" + id + "/transitions")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    reason == null
                        ? Map.of("transition", transition)
                        : Map.of("transition", transition, "reason", reason)));
    return ifMatch == null ? b : b.header("If-Match", ifMatch);
  }

  private <T> T inTask(Function<JdbcClient, T> work) throws Exception {
    JdbcClient jdbc = JdbcClient.create(taskDataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(taskTm).execute(s -> work.apply(jdbc)));
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(
        tenant, () -> mvc.perform(request.principal(caller.personId()::toString)).andReturn());
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
