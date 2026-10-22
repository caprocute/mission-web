package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import vn.viettel.okrkpi.task.application.blocker.port.BlockerNotices;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxBlockerNotices;

/**
 * Điểm nghẽn (CORE-02-20) trên PostgreSQL thật: báo, giao người xử lý, chuyển cấp trên đúng một
 * EVT-21, đóng rồi mở lại bị 409, người ngoài báo 403, sai {@code If-Match} 409, chi tiết nhiệm vụ
 * có danh sách điểm nghẽn.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TaskBlockerApiIT.BlockerTestConfig.class)
class TaskBlockerApiIT extends ContractConformanceTest {

  static final UUID LEAD = TestTaskCallers.DEFAULT_PERSON;
  static final UUID ASSIGNER = UUID.fromString("01927a02-0000-7000-8000-00000000c220");
  static final UUID HANDLER = UUID.fromString("01927a02-0000-7000-8000-00000000d220");
  static final UUID OUTSIDER = UUID.fromString("01927a02-0000-7000-8000-00000000e220");
  static final int BUCKET = 20293;

  static class BlockerTestConfig {

    @Bean
    @Primary
    BlockerNotices blockerTestNotices(
        @Qualifier("taskDataSource") DataSource ds, ObjectMapper json) {
      return new OutboxBlockerNotices(
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
                                    + " updated_by) VALUES (?, 'Q3-2029-VT', 'Quý III năm 2029',"
                                    + " 'QUY', 'NHA_NUOC', DATE '2029-07-01', DATE '2029-09-30',"
                                    + " ?, 'MO', ?, ?)")
                            .params(period, BUCKET, LEAD, LEAD)
                            .update()));
    inTask(
        jdbc -> {
          for (String table : List.of("task", "task_assignee", "task_state_log", "task_blocker")) {
            jdbc.sql("SELECT task.fn_create_period_partitions(?::regclass, ?)")
                .params("task." + table, BUCKET)
                .query()
                .listOfRows();
          }
          return null;
        });
  }

  @Test
  void baoXuLyChuyenCapTrenVaDong() throws Exception {
    UUID task = task("DANG_THUC_HIEN");
    MvcResult reported = perform(report(task));
    assertThat(reported.getResponse().getStatus()).isEqualTo(201);
    JsonNode b = body(reported).get("blockers").get(0);
    assertThat(b.get("status").asText()).isEqualTo("MO");
    assertThat(b.get("code").asText()).matches("BN-[0-9a-f]{8}-01");
    UUID blocker = UUID.fromString(b.get("id").asText());

    as(new TaskActor(OUTSIDER, Set.of("member")));
    assertThat(perform(report(task)).getResponse().getStatus()).isEqualTo(403);

    as(new TaskActor(ASSIGNER, Set.of("manager")));
    assertThat(
            perform(handle(task, blocker, 5, Map.of("status", "DONG"))).getResponse().getStatus())
        .isEqualTo(409);
    MvcResult handled = perform(handle(task, blocker, 0, Map.of("handlerPersonId", HANDLER)));
    assertThat(handled.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(handled).get("status").asText()).isEqualTo("DANG_XU_LY");

    long before = events(blocker);
    MvcResult escalated =
        perform(
            post("/api/v1/tasks/" + task + "/blockers/" + blocker + "/escalate")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("targetPersonId", OUTSIDER, "note", "Cần cấp trên hỗ trợ"))));
    assertThat(escalated.getResponse().getStatus()).isEqualTo(200);
    assertThat(events(blocker)).isEqualTo(before + 1);

    MvcResult closed =
        perform(handle(task, blocker, 2, Map.of("status", "DONG", "resolutionNote", "Đã xong")));
    assertThat(closed.getResponse().getStatus()).isEqualTo(200);
    assertThat(perform(handle(task, blocker, 3, Map.of("status", "MO"))).getResponse().getStatus())
        .isEqualTo(409);

    TestTaskCallers.reset();
    JsonNode detail = body(perform(get("/api/v1/tasks/" + task)));
    assertThat(detail.get("blockers")).hasSize(1);
    assertThat(detail.get("blockers").get(0).get("status").asText()).isEqualTo("DONG");
  }

  @Test
  void nhiemVuDaXongKhongBaoDuoc() throws Exception {
    UUID task = task("CHO_NGHIEM_THU");
    assertThat(perform(report(task)).getResponse().getStatus()).isEqualTo(409);
  }

  @AfterEach
  void resetCaller() {
    TestTaskCallers.reset();
  }

  private void as(TaskActor actor) {
    TestTaskCallers.current = actor;
  }

  private MockHttpServletRequestBuilder report(UUID task) throws Exception {
    return post("/api/v1/tasks/" + task + "/blockers")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            json.writeValueAsString(
                Map.of(
                    "content", "Chưa có số liệu của đơn vị phối hợp",
                    "causeCategory", "PHOI_HOP",
                    "causeClaimed", "KHACH_QUAN",
                    "impactLevel", "CAO")));
  }

  private MockHttpServletRequestBuilder handle(
      UUID task, UUID blocker, int version, Map<String, Object> body) throws Exception {
    return patch("/api/v1/tasks/" + task + "/blockers/" + blocker)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header("If-Match", "\"" + version + "\"")
        .contentType("application/merge-patch+json")
        .content(json.writeValueAsString(body));
  }

  private long events(UUID blocker) throws Exception {
    return this.<Long>inTask(
        jdbc ->
            jdbc.sql("SELECT count(*) FROM task.outbox_event WHERE aggregate_id = ?")
                .param(blocker)
                .query(Long.class)
                .single());
  }

  private UUID task(String status) throws Exception {
    UUID id = UUID.randomUUID();
    inTask(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket,"
                      + " code, title, task_group, source, assigner_id, due_date, unit,"
                      + " dup_fingerprint, state_machine_version, status)"
                      + " VALUES (?, ?, ?, ?, ?, ?, 'Báo cáo chuyên đề', 'THUONG_XUYEN',"
                      + " 'KE_HOACH', ?, DATE '2029-09-15', 'báo cáo', ?, 'mac-dinh@2.1.0', ?)")
              .params(
                  id,
                  ASSIGNER,
                  ASSIGNER,
                  period,
                  BUCKET,
                  "NV-2029-" + id.toString().substring(0, 8),
                  ASSIGNER,
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
