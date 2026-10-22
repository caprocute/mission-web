package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import vn.viettel.okrkpi.task.application.change.port.ChangeRequestNotices;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxChangeRequestNotices;

/**
 * Đề nghị gia hạn, điều chỉnh (CORE-02-21) trên PostgreSQL thật: hai đề nghị gia hạn gửi cùng lúc
 * thì một cái 409, lý do 19 ký tự 422, xem trước có hệ số C, rút hai lần thì lần sau 409, chi tiết
 * nhiệm vụ có danh sách đề nghị.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TaskChangeRequestApiIT.ChangeTestConfig.class)
class TaskChangeRequestApiIT extends ContractConformanceTest {

  static final UUID LEAD = TestTaskCallers.DEFAULT_PERSON;
  static final UUID ASSIGNER = UUID.fromString("01927a02-0000-7000-8000-00000000c221");
  static final int BUCKET = 20294;
  static final String REASON = "Đơn vị phối hợp chưa gửi số liệu tổng hợp quý";

  static class ChangeTestConfig {

    @Bean
    @Primary
    ChangeRequestNotices changeTestNotices(
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
                                    + " updated_by) VALUES (?, 'Q4-2029-VT', 'Quý IV năm 2029',"
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

  @Test
  void haiDeNghiGiaHanCungLucMotCai409VaRutHaiLan() throws Exception {
    UUID task = task();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Future<Integer>> results;
    try {
      Callable<Integer> send =
          () -> perform(submit(task, extension("2029-12-05", REASON))).getResponse().getStatus();
      results = pool.invokeAll(List.of(send, send));
    } finally {
      pool.shutdown();
    }
    List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
    assertThat(statuses).containsExactlyInAnyOrder(201, 409);

    JsonNode detail = body(perform(get("/api/v1/tasks/" + task)));
    assertThat(detail.get("changeRequests")).hasSize(1);
    JsonNode request = detail.get("changeRequests").get(0);
    assertThat(request.get("status").asText()).isEqualTo("CHO_DUYET");
    assertThat(detail.get("extensionsMax").asInt()).isEqualTo(2);

    String withdraw =
        "/api/v1/tasks/" + task + "/change-requests/" + request.get("id").asText() + "/withdraw";
    MvcResult first =
        perform(post(withdraw).header("Idempotency-Key", UUID.randomUUID().toString()));
    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(first).get("changeRequests").get(0).get("status").asText()).isEqualTo("DA_RUT");
    assertThat(
            perform(post(withdraw).header("Idempotency-Key", UUID.randomUUID().toString()))
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    // Đã rút thì gửi lại được.
    assertThat(perform(submit(task, extension("2029-12-05", REASON))).getResponse().getStatus())
        .isEqualTo(201);
  }

  @Test
  void lyDoNgan422VaXemTruoc() throws Exception {
    UUID task = task();
    assertThat(
            perform(submit(task, extension("2029-12-05", "x".repeat(19))))
                .getResponse()
                .getStatus())
        .isEqualTo(422);
    MvcResult preview =
        perform(
            post("/api/v1/tasks/" + task + "/change-requests/preview")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(extension("2029-12-05", REASON))));
    assertThat(preview.getResponse().getStatus()).isEqualTo(200);
    JsonNode p = body(preview);
    assertThat(p.get("objective").get("c").decimalValue()).isEqualByComparingTo("1");
    assertThat(p.get("subjective").get("delayDays").asInt())
        .isEqualTo(p.get("extraWorkingDays").asInt());
    assertThat(p.get("overThreshold").asBoolean()).isFalse();
    assertThat(
            this.<Long>inTask(
                jdbc ->
                    jdbc.sql("SELECT count(*) FROM task.task_change_request WHERE task_id = ?")
                        .param(task)
                        .query(Long.class)
                        .single()))
        .isZero();
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
