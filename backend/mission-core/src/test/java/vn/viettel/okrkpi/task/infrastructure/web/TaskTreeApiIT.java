package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.assign.port.TaskAssignedEvents;
import vn.viettel.okrkpi.task.application.tree.TaskTreeService;
import vn.viettel.okrkpi.task.infrastructure.persistence.JdbcTaskTreeRows;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxTaskAssignedEvents;

/**
 * Cây nhiệm vụ và chế độ phân công (CORE-02-28) trên PostgreSQL thật, chạy cùng bộ với hai giá trị
 * tham số {@code che_do_phan_cong}: {@code CHU_TRI_PHOI_HOP} giữ hành vi cũ (giao một chủ trì kèm
 * phối hợp); {@code MOT_NGUOI_MOT_VIEC} chặn việc lá hai người và tổng điểm thành phần vượt điểm
 * việc cha với 422 {@code TASK-422-ASSIGNMENT-MODE}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {"mission.outbox.relay.enabled=false"})
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TaskTreeApiIT.TreeTestConfig.class)
class TaskTreeApiIT extends ContractConformanceTest {

  static final UUID ASSIGNER = TestTaskCallers.DEFAULT_PERSON;
  static final UUID LEAD = UUID.fromString("01927a02-0000-7000-8000-00000000c228");
  static final UUID UNIT = UUID.fromString("01927a02-0000-7000-8000-00000000d228");
  static final UUID HEAD = UUID.fromString("01927a02-0000-7000-8000-00000000e228");
  static final UUID CO = UUID.fromString("01927a02-0000-7000-8000-00000000f228");
  static final int BUCKET = 20284;

  /** Giá trị tham số {@code che_do_phan_cong} của gói giả; rỗng là gói không khai báo. */
  static final AtomicReference<String> MODE = new AtomicReference<>();

  /**
   * Outbox với bộ tuần tự giả, bản sao tổ chức cố định (mọi người công tác tại {@link #UNIT}) và
   * dịch vụ cây đọc tham số từ gói giả.
   */
  static class TreeTestConfig {

    @Bean
    @Primary
    TaskTreeService treeTestTreeService(@Qualifier("taskDataSource") DataSource ds) {
      UUID version = UUID.randomUUID();
      PolicyQueryApi pack =
          new PolicyQueryApi() {
            @Override
            public Optional<PolicyFormulaView> findActivePolicy(
                TenantId tenantId, PeriodId periodId, String code) {
              return Optional.empty();
            }

            @Override
            public Optional<ResolvedPack> resolve(
                TenantId tenantId, PeriodId periodId, String flow, UUID group, UUID unit) {
              return Optional.of(
                  new ResolvedPack(
                      UUID.randomUUID(), "PP-CAY", version, "1.0.0", "h", null, version, false));
            }

            @Override
            public Optional<Object> parameter(TenantId tenantId, UUID versionId, String key) {
              return TaskTreeService.MODE_KEY.equals(key)
                  ? Optional.ofNullable(MODE.get())
                  : Optional.empty();
            }
          };
      return new TaskTreeService(new JdbcTaskTreeRows(JdbcClient.create(ds)), pack);
    }

    @Bean
    @Primary
    TaskAssignedEvents treeTestAssignedEvents(
        @Qualifier("taskDataSource") DataSource ds, ObjectMapper json) {
      return new OutboxTaskAssignedEvents(
          new OutboxWriter(
              new JdbcTemplate(ds),
              "task",
              (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
              json,
              Clock.systemUtc()),
          json);
    }

    @Bean
    @Primary
    OrgDirectoryApi treeTestOrgDirectory() {
      return new OrgDirectoryApi() {
        @Override
        public List<AssignmentView> assignmentsOf(TenantId tenant, UUID personId, LocalDate on) {
          return List.of(
              new AssignmentView(
                  UUID.randomUUID(),
                  personId,
                  UNIT,
                  null,
                  null,
                  "CHINH",
                  "CHINH",
                  false,
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
                                    + " updated_by) VALUES (?, 'Q4-2028-CAY', 'Quý IV năm 2028',"
                                    + " 'QUY', 'NHA_NUOC', DATE '2028-10-01', DATE '2028-12-31',"
                                    + " ?, 'MO', ?, ?)")
                            .params(period, BUCKET, ASSIGNER, ASSIGNER)
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

  @AfterEach
  void resetMode() {
    MODE.set(null);
  }

  @Test
  void cheDoMacDinhGiuHanhViCuVaLuuViTriTrongCay() throws Exception {
    String parent = assigned(draft("Triển khai chuyển đổi số"), null);

    ObjectNode child = draft("Số hoá hồ sơ lưu trữ");
    child.put("nodeKind", "CONG_VIEC");
    child.put("parentTaskId", parent);
    child.put("componentScore", 30);
    child.putArray("coAssignees").addObject().put("kind", "PERSON").put("id", CO.toString());
    MvcResult created = perform(write(post("/api/v1/tasks"), null).content(child.toString()));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    JsonNode saved = body(created);
    assertThat(saved.get("nodeKind").asText()).isEqualTo("CONG_VIEC");
    assertThat(saved.get("parentTaskId").asText()).isEqualTo(parent);
    assertThat(saved.get("componentScore").decimalValue()).isEqualByComparingTo("30");

    // Mặc định: một chủ trì kèm phối hợp vẫn giao được.
    String id = saved.get("id").asText();
    MvcResult ok =
        perform(
            write(
                post("/api/v1/tasks/" + id + "/assign"), created.getResponse().getHeader("ETag")));
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    MvcResult detail = perform(get("/api/v1/tasks/" + id));
    assertThat(body(detail).get("nodeKind").asText()).isEqualTo("CONG_VIEC");
    assertThat(body(detail).get("parentTaskId").asText()).isEqualTo(parent);

    ObjectNode wrong = draft("Việc cùng bậc với việc cha");
    wrong.put("nodeKind", "NHIEM_VU");
    wrong.put("parentTaskId", parent);
    MvcResult bad = perform(write(post("/api/v1/tasks"), null).content(wrong.toString()));
    assertThat(bad.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(bad).get("errors").get(0).get("field").asText()).isEqualTo("nodeKind");

    ObjectNode orphan = draft("Việc có cha không tồn tại");
    orphan.put("nodeKind", "CONG_VIEC");
    orphan.put("parentTaskId", UUID.randomUUID().toString());
    MvcResult missing = perform(write(post("/api/v1/tasks"), null).content(orphan.toString()));
    assertThat(missing.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(missing).get("errors").get(0).get("field").asText()).isEqualTo("parentTaskId");
  }

  @Test
  void motNguoiMotViecChanViecLaHaiNguoiVaLechTongDiem() throws Exception {
    MODE.set("MOT_NGUOI_MOT_VIEC");
    String parent = assigned(draft("Xây dựng báo cáo chuyên đề"), null);
    inTask(
        jdbc ->
            jdbc.sql("UPDATE task.task SET standard_score = 50 WHERE id = ?")
                .param(UUID.fromString(parent))
                .update());

    ObjectNode two = draft("Thu thập số liệu chuyên đề");
    two.put("nodeKind", "CONG_VIEC");
    two.put("parentTaskId", parent);
    two.put("componentScore", 30);
    two.putArray("coAssignees").addObject().put("kind", "PERSON").put("id", CO.toString());
    MvcResult check =
        perform(
            post("/api/v1/tasks/assign-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(two.toString()));
    assertThat(body(check).get("items").toString()).contains("DC-06");
    MvcResult created = perform(write(post("/api/v1/tasks"), null).content(two.toString()));
    String twoId = body(created).get("id").asText();
    MvcResult blocked =
        perform(
            write(
                post("/api/v1/tasks/" + twoId + "/assign"),
                created.getResponse().getHeader("ETag")));
    assertThat(blocked.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(blocked).get("code").asText()).isEqualTo("TASK-422-ASSIGNMENT-MODE");
    assertThat(body(blocked).get("errors").get(0).get("field").asText()).isEqualTo("coAssignees");

    ObjectNode one = draft("Phân tích số liệu chuyên đề");
    one.put("nodeKind", "CONG_VIEC");
    one.put("parentTaskId", parent);
    one.put("componentScore", 30);
    assigned(one, null);

    ObjectNode over = draft("Viết dự thảo báo cáo chuyên đề");
    over.put("nodeKind", "CONG_VIEC");
    over.put("parentTaskId", parent);
    over.put("componentScore", 30);
    MvcResult overCreated = perform(write(post("/api/v1/tasks"), null).content(over.toString()));
    MvcResult overBlocked =
        perform(
            write(
                post("/api/v1/tasks/" + body(overCreated).get("id").asText() + "/assign"),
                overCreated.getResponse().getHeader("ETag")));
    assertThat(overBlocked.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(overBlocked).get("errors").get(0).get("field").asText())
        .isEqualTo("componentScore");
  }

  private String assigned(ObjectNode d, String reason) throws Exception {
    MvcResult created = perform(write(post("/api/v1/tasks"), null).content(d.toString()));
    String id = body(created).get("id").asText();
    MockHttpServletRequestBuilder req =
        write(post("/api/v1/tasks/" + id + "/assign"), created.getResponse().getHeader("ETag"));
    if (reason != null) {
      req.content(json.writeValueAsString(java.util.Map.of("similarReason", reason)));
    }
    MvcResult done = perform(req);
    assertThat(done.getResponse().getStatus()).isEqualTo(200);
    return id;
  }

  private ObjectNode draft(String title) {
    ObjectNode d = json.createObjectNode();
    d.put("status", "NHAP");
    d.put("periodId", period.toString());
    d.put("unitName", "");
    d.put("assignerId", ASSIGNER.toString());
    d.put("assignerName", "");
    d.put("source", "DOT_XUAT");
    d.put("title", title);
    d.put("taskGroup", "THUONG_XUYEN");
    d.put("priority", "TRUNG_BINH");
    d.put("leadPersonId", LEAD.toString());
    d.putArray("coAssignees");
    d.putArray("milestones");
    d.put("dueDate", "2028-12-15");
    d.put("productName", "Báo cáo rà soát");
    d.put("quantity", 1);
    d.put("unit", "bản");
    d.put("evidenceRequired", "Tệp báo cáo có chữ ký");
    d.put("qualityCriteria", "Đúng mẫu, đủ số liệu");
    d.put("basis", "Chỉ đạo của thủ trưởng");
    return d;
  }

  private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b, String ifMatch) {
    b.header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON);
    return ifMatch == null ? b : b.header("If-Match", ifMatch);
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
