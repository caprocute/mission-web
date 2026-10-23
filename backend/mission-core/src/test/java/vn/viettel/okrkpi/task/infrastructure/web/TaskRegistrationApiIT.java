package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.assign.port.RegistrationNotices;
import vn.viettel.okrkpi.task.application.assign.port.TaskAssignedEvents;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;

/**
 * Đăng ký nhiệm vụ (CORE-02-17) trên PostgreSQL thật: mục không áp dụng cho vị trí trả 422; giao
 * nhiệm vụ đăng ký chưa duyệt bị chặn với {@code rule} BR-05-08; người đăng ký tự duyệt trả 403;
 * duyệt khi chưa có điều chỉnh kế hoạch thì báo người lập; có phiên bản điều chỉnh đang soạn thì
 * mỗi nhiệm vụ đăng ký đã duyệt có đúng một dòng; từ chối bắt buộc lý do và huỷ nhiệm vụ.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TaskRegistrationApiIT.RegistrationTestConfig.class)
class TaskRegistrationApiIT extends ContractConformanceTest {

  static final UUID REG = UUID.fromString("01927a02-0000-7000-8000-00000000c217");
  static final UUID HEAD = UUID.fromString("01927a02-0000-7000-8000-00000000e217");
  static final UUID UNIT = UUID.fromString("01927a02-0000-7000-8000-00000000d217");
  static final UUID POSITION = UUID.fromString("01927a02-0000-7000-8000-00000000f217");

  /** Kỳ đang mở là quý hiện tại (đăng ký luôn vào kỳ hiện hành). */
  static final LocalDate START =
      LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
          .withMonth(
              (LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).getMonthValue() - 1) / 3 * 3
                  + 1)
          .withDayOfMonth(1);

  static final LocalDate END = START.plusMonths(3).minusDays(1);
  static final int BUCKET = START.getYear() * 10 + (START.getMonthValue() - 1) / 3 + 1;

  /** Bản sao tổ chức cố định: mọi người công tác tại {@link #UNIT}, vị trí {@link #POSITION}. */
  static class RegistrationTestConfig {

    @Bean
    @Primary
    OrgDirectoryApi registrationTestOrgDirectory() {
      return new OrgDirectoryApi() {
        @Override
        public List<AssignmentView> assignmentsOf(TenantId tenant, UUID personId, LocalDate on) {
          return List.of(
              new AssignmentView(
                  UUID.randomUUID(),
                  personId,
                  UNIT,
                  POSITION,
                  null,
                  "CHINH",
                  "CHINH",
                  HEAD.equals(personId),
                  on,
                  null));
        }

        @Override
        public List<UUID> headsOf(TenantId tenant, UUID orgUnitId, LocalDate on) {
          return List.of(HEAD);
        }

        @Override
        public Optional<UUID> parentOf(TenantId tenant, OrgTree tree, UUID orgUnitId) {
          return Optional.empty();
        }

        @Override
        public List<UUID> descendantsOf(
            TenantId tenant, OrgTree tree, UUID orgUnitId, int maxDepth) {
          return List.of();
        }

        @Override
        public List<UUID> ancestorsOf(TenantId tenant, OrgTree tree, UUID orgUnitId) {
          return List.of();
        }

        @Override
        public boolean isDescendant(TenantId tenant, OrgTree tree, UUID ancestor, UUID candidate) {
          return ancestor.equals(candidate);
        }
      };
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

  @MockitoBean private RegistrationNotices notices;
  @MockitoBean private TaskAssignedEvents assignedEvents;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private final UUID applicable = UUID.randomUUID();
  private final UUID foreign = UUID.randomUUID();

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
                                    + " updated_by) VALUES (?, 'DK-' || ?, 'Quý đăng ký', 'QUY',"
                                    + " 'NHA_NUOC', ?, ?, ?, 'MO', ?, ?)")
                            .params(period, BUCKET, START, END, BUCKET, HEAD, HEAD)
                            .update()));
    inTask(
        jdbc -> {
          for (String table :
              List.of(
                  "task", "task_assignee", "task_state_log", "plan", "plan_version", "plan_item")) {
            jdbc.sql("SELECT task.fn_create_period_partitions(?::regclass, ?)")
                .params("task." + table, BUCKET)
                .query()
                .listOfRows();
          }
          catalog(jdbc, POSITION, applicable, "DK-01");
          catalog(jdbc, UUID.randomUUID(), foreign, "DK-02");
          return null;
        });
  }

  @AfterEach
  void resetCaller() {
    TestTaskCallers.reset();
  }

  @Test
  void dangKyDuyetTuChoiVaDuaVaoDieuChinhKeHoach() throws Exception {
    as(REG, "member");
    assertThat(perform(register(foreign)).getResponse().getStatus()).isEqualTo(422);

    MvcResult created = perform(register(applicable));
    assertThat(created.getResponse().getStatus())
        .as(created.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(201);
    assertResponseConforms(created, "/tasks/registrations", PathItem.HttpMethod.POST);
    String r1 = body(created).get("id").asText();
    String etag = created.getResponse().getHeader("ETag");
    assertThat(body(created).get("status").asText()).isEqualTo("NHAP");
    verify(notices).submitted(eq(tenant), eq(UUID.fromString(r1)), any(), eq(List.of(HEAD)));

    // Hàng đăng ký chờ duyệt của người giao việc (QT-05 bước 3): lọc NHAP nguồn DANG_KY.
    as(HEAD, "manager");
    MvcResult queue = perform(unitTasks("status=NHAP&source=DANG_KY"));
    assertThat(queue.getResponse().getStatus())
        .as(queue.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(200);
    assertResponseConforms(queue, "/tasks", PathItem.HttpMethod.GET);
    assertThat(body(queue).get("items").findValuesAsText("id")).containsExactly(r1);
    assertThat(body(queue).get("items").get(0).get("source").asText()).isEqualTo("DANG_KY");
    assertThat(body(perform(unitTasks("source=KE_HOACH"))).get("items")).isEmpty();
    assertThat(body(perform(unitTasks("pendingChangeRequest=true"))).get("items")).isEmpty();
    assertThat(
            body(perform(unitTasks("pendingChangeRequest=false")))
                .get("items")
                .findValuesAsText("id"))
        .containsExactly(r1);

    // Giao thông thường bị chặn (BR-05-08); người đăng ký tự duyệt bị 403.
    MvcResult blocked = perform(write(post("/api/v1/tasks/" + r1 + "/assign"), etag));
    assertThat(blocked.getResponse().getStatus()).isEqualTo(422);
    assertThat(blocked.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .contains("BR-05-08");
    as(REG, "manager");
    assertThat(perform(decide(r1, etag, Map.of("approve", true))).getResponse().getStatus())
        .isEqualTo(403);

    // Duyệt khi chưa có điều chỉnh kế hoạch: giao, báo người lập (người quản lý vì chưa có kế
    // hoạch).
    as(HEAD, "manager");
    MvcResult approved = perform(decide(r1, etag, Map.of("approve", true, "quantity", 2)));
    assertThat(approved.getResponse().getStatus())
        .as(approved.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(200);
    assertResponseConforms(approved, "/tasks/{id}/registration-decision", PathItem.HttpMethod.POST);
    assertThat(body(approved).get("status").asText()).isEqualTo("DA_GIAO");
    verify(notices)
        .waitingForAdjustment(eq(tenant), eq(UUID.fromString(r1)), any(), eq(List.of(HEAD)));

    // Có phiên bản điều chỉnh đang soạn: mỗi nhiệm vụ đăng ký đã duyệt có đúng một dòng.
    UUID v2 = adjustment();
    as(REG, "member");
    MvcResult second = perform(register(applicable));
    String r2 = body(second).get("id").asText();
    as(HEAD, "manager");
    assertThat(
            perform(decide(r2, second.getResponse().getHeader("ETag"), Map.of("approve", true)))
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    for (String id : List.of(r1, r2)) {
      assertThat(
              count(
                  "SELECT count(*) FROM task.plan_item i JOIN task.task t ON t.plan_item_id = i.id"
                      + " WHERE t.id = ? AND i.plan_version_id = ?"
                      + " AND i.change_reason = 'Đăng ký nhiệm vụ'",
                  UUID.fromString(id),
                  v2))
          .isEqualTo(1);
    }
    assertThat(count("SELECT count(*) FROM task.plan_item WHERE plan_version_id = ?", v2))
        .isEqualTo(3);

    // Từ chối: thiếu lý do 422; có lý do thì huỷ và báo người đăng ký.
    as(REG, "member");
    MvcResult third = perform(register(applicable));
    String r3 = body(third).get("id").asText();
    String e3 = third.getResponse().getHeader("ETag");
    as(HEAD, "manager");
    assertThat(perform(decide(r3, e3, Map.of("approve", false))).getResponse().getStatus())
        .isEqualTo(422);
    MvcResult rejected =
        perform(decide(r3, e3, Map.of("approve", false, "reason", "Trùng việc đã giao")));
    assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(rejected).get("status").asText()).isEqualTo("HUY");
    verify(notices).rejected(eq(tenant), eq(UUID.fromString(r3)), any(), eq(REG));
    assertThat(
            count(
                "SELECT count(*) FROM task.outbox_event WHERE aggregate_id = ?"
                    + " AND topic = 'task.task.statuschanged.v1'",
                UUID.fromString(r3)))
        .isEqualTo(1);
  }

  private void catalog(JdbcClient jdbc, UUID position, UUID item, String code) {
    UUID catalog = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO task.work_catalog (id, created_by, updated_by, code, name, scope,"
                + " position_id, version, source, status, version_no) VALUES (?, ?, ?, ?,"
                + " 'Danh mục vị trí', 'VI_TRI', ?, 1, 'TU_TAO', 'HIEU_LUC', 1)")
        .params(catalog, HEAD, HEAD, "C-" + code, position)
        .update();
    jdbc.sql(
            "INSERT INTO task.catalog_item (id, created_by, updated_by, work_catalog_id, code,"
                + " name, product_name, unit, difficulty, standard_score, quality_standard,"
                + " evidence_requirement, task_group, status) VALUES (?, ?, ?, ?, ?,"
                + " 'Tổng hợp báo cáo tuần', 'Báo cáo', 'báo cáo', 2, 5, 'Đúng mẫu, đúng hạn',"
                + " 'Văn bản đã ký', 'THUONG_XUYEN', 'AP_DUNG')")
        .params(item, HEAD, HEAD, catalog, code)
        .update();
  }

  /** Kế hoạch của đơn vị đã duyệt phiên bản 1, phiên bản 2 đang soạn; trả phiên bản 2. */
  private UUID adjustment() throws Exception {
    UUID plan = UUID.randomUUID();
    UUID v1 = UUID.randomUUID();
    UUID v2 = UUID.randomUUID();
    inTask(
        jdbc -> {
          jdbc.sql(
                  "INSERT INTO task.plan (id, created_by, updated_by, period_id, period_bucket,"
                      + " plan_type, owner_unit_id) VALUES (?, ?, ?, ?, ?, 'TAP_THE', ?)")
              .params(plan, HEAD, HEAD, period, BUCKET, UNIT)
              .update();
          jdbc.sql(
                  "INSERT INTO task.plan_version (id, created_by, updated_by, period_id,"
                      + " period_bucket, plan_id, version_no, status, is_current, approved_by,"
                      + " approved_at) VALUES (?, ?, ?, ?, ?, ?, 1, 'DA_DUYET', true, ?, now())")
              .params(v1, REG, REG, period, BUCKET, plan, HEAD)
              .update();
          jdbc.sql(
                  "INSERT INTO task.plan_version (id, created_by, updated_by, period_id,"
                      + " period_bucket, plan_id, version_no, status, change_reason) VALUES (?,"
                      + " ?, ?, ?, ?, ?, 2, 'NHAP', 'Bổ sung')")
              .params(v2, REG, REG, period, BUCKET, plan)
              .update();
          return jdbc.sql(
                  "INSERT INTO task.plan_item (id, created_by, updated_by, period_id,"
                      + " period_bucket, plan_version_id, item_no, title, task_group,"
                      + " lead_unit_id, planned_quantity, unit, due_date) VALUES (?, ?, ?, ?, ?,"
                      + " ?, 1, 'Việc có sẵn', 'THUONG_XUYEN', ?, 1, 'báo cáo', ?)")
              .params(UUID.randomUUID(), REG, REG, period, BUCKET, v2, UNIT, END)
              .update();
        });
    return v2;
  }

  private MockHttpServletRequestBuilder register(UUID item) throws Exception {
    return write(post("/api/v1/tasks/registrations"), null)
        .content(
            json.writeValueAsString(
                Map.of(
                    "catalogItemId",
                    item,
                    "quantity",
                    1,
                    "proposedDueDate",
                    END.toString(),
                    "note",
                    "Đề xuất theo nhu cầu đơn vị")));
  }

  private MockHttpServletRequestBuilder unitTasks(String query) {
    return get("/api/v1/tasks?unit=" + UNIT + "&period=" + period + "&" + query);
  }

  private MockHttpServletRequestBuilder decide(String id, String etag, Map<String, Object> body)
      throws Exception {
    return write(post("/api/v1/tasks/" + id + "/registration-decision"), etag)
        .content(json.writeValueAsString(body));
  }

  private static void as(UUID person, String role) {
    TestTaskCallers.current = new TaskActor(person, Set.of(role));
  }

  private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b, String ifMatch) {
    b.header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON);
    return ifMatch == null ? b : b.header("If-Match", ifMatch);
  }

  private long count(String sql, Object... params) throws Exception {
    return inTask(jdbc -> jdbc.sql(sql).params(params).query(Long.class).single());
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
