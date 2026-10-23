package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
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
import vn.viettel.okrkpi.shared.infrastructure.web.InMemoryJobRegistry;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;

/**
 * "Việc của tôi", nhận việc và xuất Excel (CORE-02-18) trên PostgreSQL thật: số đếm bộ lọc nhanh,
 * duyệt hết bằng con trỏ không trùng không sót, chỉ thấy việc của mình, "Trễ n ngày" âm, nhận việc
 * của người phối hợp 403, gửi lại cùng khoá chỉ một EVT-06, tệp xuất chỉ người tạo tải được.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyTaskApiIT extends ContractConformanceTest {

  static final UUID ME = TestTaskCallers.DEFAULT_PERSON;
  static final UUID OTHER = UUID.fromString("01927a02-0000-7000-8000-00000000c218");
  static final int LIST_BUCKET = 20291;
  static final int ACT_BUCKET = 20292;

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
  private final UUID listPeriod = UUID.randomUUID();
  private final UUID actPeriod = UUID.randomUUID();
  private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

  @Override
  protected ContractModule contractModule() {
    return ContractModule.TASKS;
  }

  @BeforeAll
  void seed() throws Exception {
    period(listPeriod, "Q1-2029-VT", "Quý I năm 2029", LIST_BUCKET, "2029-01-01", "2029-03-31");
    period(actPeriod, "Q2-2029-VT", "Quý II năm 2029", ACT_BUCKET, "2029-04-01", "2029-06-30");
    inTask(
        jdbc -> {
          for (int bucket : List.of(LIST_BUCKET, ACT_BUCKET)) {
            for (String table :
                List.of(
                    "task", "task_assignee", "task_state_log", "task_progress", "task_blocker")) {
              jdbc.sql("SELECT task.fn_create_period_partitions(?::regclass, ?)")
                  .params("task." + table, bucket)
                  .query()
                  .listOfRows();
            }
          }
          return null;
        });
  }

  @Test
  void danhSachSoDemConTroVaBoLoc() throws Exception {
    UUID late =
        task(listPeriod, LIST_BUCKET, "DANG_THUC_HIEN", today.minusDays(10), true, ME, "CHU_TRI");
    UUID soon = task(listPeriod, LIST_BUCKET, "DA_GIAO", today.plusDays(1), false, ME, "CHU_TRI");
    task(listPeriod, LIST_BUCKET, "CHO_NGHIEM_THU", today.plusDays(30), false, ME, "CHU_TRI");
    task(listPeriod, LIST_BUCKET, "HOAN_THANH", today.minusDays(3), false, ME, "CHU_TRI");
    UUID co = task(listPeriod, LIST_BUCKET, "DA_GIAO", today.plusDays(40), false, ME, "PHOI_HOP");
    task(listPeriod, LIST_BUCKET, "DA_GIAO", today.plusDays(2), false, OTHER, "CHU_TRI");

    JsonNode first = body(perform(mine().param("limit", "2")));
    JsonNode counts = first.get("counts");
    assertThat(counts.get("TAT_CA").asInt()).isEqualTo(5);
    assertThat(counts.get("CHAM").asInt()).isEqualTo(1);
    assertThat(counts.get("SAP_HAN").asInt()).isEqualTo(1);
    assertThat(counts.get("CHO_NGHIEM_THU").asInt()).isEqualTo(1);
    assertThat(counts.get("HOAN_THANH").asInt()).isEqualTo(1);
    assertThat(first.get("items").get(0).get("id").asText()).isEqualTo(late.toString());
    assertThat(first.get("items").get(0).get("workingDaysLeft").asInt()).isNegative();
    assertThat(first.get("items").get(0).get("permittedActions").toString())
        .isEqualTo("[\"SCR-0503.tien-do\"]");

    List<String> ids = new ArrayList<>();
    JsonNode page = first;
    while (true) {
      page.get("items").forEach(i -> ids.add(i.get("id").asText()));
      if (page.get("nextCursor").isNull()) {
        break;
      }
      page =
          body(
              perform(mine().param("limit", "2").param("cursor", page.get("nextCursor").asText())));
    }
    assertThat(ids).hasSize(5).doesNotHaveDuplicates();

    assertThat(ids(perform(mine().param("filter", "CHAM")))).containsExactly(late.toString());
    assertThat(ids(perform(mine().param("role", "PHOI_HOP")))).containsExactly(co.toString());
    // Phối hợp, nhiệm vụ còn DA_GIAO: chưa nhận việc, chưa cập nhật tiến độ được (YC-PT-21).
    assertThat(
            body(perform(mine().param("role", "PHOI_HOP")))
                .get("items")
                .get(0)
                .get("permittedActions")
                .isEmpty())
        .isTrue();
    assertThat(ids(perform(mine().param("view", "today"))))
        .containsExactly(late.toString(), soon.toString());
  }

  @Test
  void nhanViecChiChuTriVaGuiLaiKhongTrung() throws Exception {
    UUID lead = task(actPeriod, ACT_BUCKET, "DA_GIAO", today.plusDays(20), false, ME, "CHU_TRI");
    UUID co = task(actPeriod, ACT_BUCKET, "DA_GIAO", today.plusDays(20), false, ME, "PHOI_HOP");
    String key = UUID.randomUUID().toString();
    MvcResult ok = perform(accept(lead, key));
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(ok).get("status").asText()).isEqualTo("DANG_THUC_HIEN");
    assertThat(perform(accept(lead, key)).getResponse().getStatus()).isEqualTo(200);
    assertThat(
            this.<Long>inTask(
                jdbc ->
                    jdbc.sql("SELECT count(*) FROM task.outbox_event WHERE aggregate_id = ?")
                        .param(lead)
                        .query(Long.class)
                        .single()))
        .isEqualTo(1);
    assertThat(
            this.<Long>inTask(
                jdbc ->
                    jdbc.sql(
                            "SELECT count(*) FROM task.task_assignee WHERE task_id = ?"
                                + " AND accepted_at IS NOT NULL")
                        .param(lead)
                        .query(Long.class)
                        .single()))
        .isEqualTo(1);
    assertThat(perform(accept(co, UUID.randomUUID().toString())).getResponse().getStatus())
        .isEqualTo(403);
  }

  @Test
  void xuatExcelChiNguoiTaoTaiDuoc() throws Exception {
    task(actPeriod, ACT_BUCKET, "DANG_THUC_HIEN", today.plusDays(5), false, ME, "CHU_TRI");
    MvcResult accepted =
        perform(
            post("/api/v1/tasks/export")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("assignee", "me", "period", actPeriod))));
    assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
    String job = accepted.getResponse().getHeader("Location");
    JsonNode status = null;
    InMemoryJobRegistry.setActorOverride(ActorId.of(ME));
    try {
      for (int i = 0; i < 100; i++) {
        status = body(perform(get(job)));
        if ("HOAN_TAT".equals(status.get("status").asText())) {
          break;
        }
        Thread.sleep(100);
      }
    } finally {
      InMemoryJobRegistry.clearActorOverride();
    }
    assertThat(status.get("status").asText()).isEqualTo("HOAN_TAT");
    String location = status.get("resultLocation").asText();
    MvcResult file = perform(get(location));
    assertThat(file.getResponse().getStatus()).isEqualTo(200);
    byte[] bytes = file.getResponse().getContentAsByteArray();
    assertThat(new String(bytes, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
    TestTaskCallers.current = new TaskActor(OTHER, Set.of("member"));
    try {
      assertThat(perform(get(location)).getResponse().getStatus()).isEqualTo(404);
    } finally {
      TestTaskCallers.reset();
    }
  }

  private MockHttpServletRequestBuilder mine() {
    return get("/api/v1/tasks/mine").param("period", listPeriod.toString());
  }

  private MockHttpServletRequestBuilder accept(UUID id, String key) {
    return post("/api/v1/tasks/" + id + "/accept")
        .header("Idempotency-Key", key)
        .header("If-Match", "\"0\"");
  }

  private List<String> ids(MvcResult r) throws Exception {
    List<String> out = new ArrayList<>();
    body(r).get("items").forEach(i -> out.add(i.get("id").asText()));
    return out;
  }

  private void period(UUID id, String code, String name, int bucket, String start, String end)
      throws Exception {
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
                                    + " updated_by) VALUES (?, ?, ?, 'QUY', 'NHA_NUOC', ?::date,"
                                    + " ?::date, ?, 'MO', ?, ?)")
                            .params(id, code, name, start, end, bucket, ME, ME)
                            .update()));
  }

  private UUID task(
      UUID period,
      int bucket,
      String status,
      LocalDate due,
      boolean overdue,
      UUID person,
      String role)
      throws Exception {
    UUID id = UUID.randomUUID();
    inTask(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket,"
                      + " code, title, task_group, source, assigner_id, due_date, unit,"
                      + " dup_fingerprint, state_machine_version, status, is_overdue, completed_at)"
                      + " VALUES (?, ?, ?, ?, ?, ?, 'Báo cáo chuyên đề', 'THUONG_XUYEN',"
                      + " 'KE_HOACH', ?, ?, 'báo cáo', ?, 'mac-dinh@2.1.0', ?, ?, CASE WHEN ?"
                      + " = 'HOAN_THANH' THEN now() END)")
              .params(
                  id,
                  OTHER,
                  OTHER,
                  period,
                  bucket,
                  "NV-" + id.toString().substring(0, 8),
                  OTHER,
                  Date.valueOf(due),
                  id.toString().getBytes(StandardCharsets.UTF_8),
                  status,
                  overdue,
                  status)
              .update();
          jdbc.sql(
                  "INSERT INTO task.task_assignee (id, created_by, updated_by, period_id,"
                      + " period_bucket, task_id, assignee_type, person_id, role, valid_from)"
                      + " VALUES (?, ?, ?, ?, ?, ?, 'CA_NHAN', ?, ?, DATE '2029-01-01')")
              .params(UUID.randomUUID(), OTHER, OTHER, period, bucket, id, person, role)
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
