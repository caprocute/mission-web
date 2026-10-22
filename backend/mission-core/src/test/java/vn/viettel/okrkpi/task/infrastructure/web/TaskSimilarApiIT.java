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
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.assign.port.TaskAssignedEvents;
import vn.viettel.okrkpi.task.infrastructure.persistence.OutboxTaskAssignedEvents;

/**
 * Nhiệm vụ gần giống (CORE-02-15) trên PostgreSQL thật, SVC-16 trỏ tới cổng không nghe để giả lập
 * lỗi: hai nhiệm vụ cùng tên khác khoảng trắng và dấu câu có cùng {@code dup_fingerprint}; kiểm tra
 * trước khi giao cảnh báo và liệt kê nhiệm vụ gần giống; giao thiếu lý do 422 {@code rule}
 * BR-05-06, có lý do thì giao được và lý do vào nhật ký; nhiệm vụ đơn vị khác không có trong kết
 * quả.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {
      "mission.outbox.relay.enabled=false",
      "mission.task.similar.search-url=http://127.0.0.1:9",
      "mission.task.similar.timeout=PT0.3S"
    })
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TaskSimilarApiIT.SimilarTestConfig.class)
class TaskSimilarApiIT extends ContractConformanceTest {

  static final UUID ASSIGNER = TestTaskCallers.DEFAULT_PERSON;
  static final UUID LEAD = UUID.fromString("01927a02-0000-7000-8000-00000000c215");
  static final UUID UNIT = UUID.fromString("01927a02-0000-7000-8000-00000000d215");
  static final UUID HEAD = UUID.fromString("01927a02-0000-7000-8000-00000000e215");
  static final int BUCKET = 20274;

  /** Outbox với bộ tuần tự giả và bản sao tổ chức cố định: mọi người công tác tại {@link #UNIT}. */
  static class SimilarTestConfig {

    @Bean
    @Primary
    TaskAssignedEvents similarTestAssignedEvents(
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
    OrgDirectoryApi similarTestOrgDirectory() {
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
                                    + " updated_by) VALUES (?, 'Q4-2027-GG', 'Quý IV năm 2027',"
                                    + " 'QUY', 'NHA_NUOC', DATE '2027-10-01', DATE '2027-12-31',"
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

  @Test
  void ganGiongCanhBaoBatBuocLyDoVaLuuNhatKy() throws Exception {
    String first = assigned(draft("Báo cáo tổng kết công tác quý IV"), null);

    ObjectNode copy = draft("báo cáo  tổng kết, công tác quý IV.");
    MvcResult created = perform(write(post("/api/v1/tasks"), null).content(copy.toString()));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    String second = body(created).get("id").asText();
    assertThat(fingerprint(UUID.fromString(first))).isEqualTo(fingerprint(UUID.fromString(second)));

    UUID otherUnit = foreignTask("Báo cáo tổng kết công tác quý IV");
    MvcResult similar = perform(get("/api/v1/tasks/" + second + "/similar"));
    assertThat(similar.getResponse().getStatus()).isEqualTo(200);
    JsonNode items = body(similar).get("items");
    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("id").asText()).isEqualTo(first);
    assertThat(items.get(0).get("similarity").decimalValue()).isEqualByComparingTo("1");
    assertThat(items.toString()).doesNotContain(otherUnit.toString());

    MvcResult check =
        perform(
            post("/api/v1/tasks/assign-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(copy.toString()));
    assertThat(check.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(check).get("similar").get(0).get("id").asText()).isEqualTo(first);
    assertThat(body(check).get("items").toString()).contains("BR-05-06");

    String etag = created.getResponse().getHeader("ETag");
    MvcResult blocked = perform(write(post("/api/v1/tasks/" + second + "/assign"), etag));
    assertThat(blocked.getResponse().getStatus()).isEqualTo(422);
    assertThat(body(blocked).get("errors").get(0).get("rule").asText()).isEqualTo("BR-05-06");

    MvcResult ok =
        perform(
            write(post("/api/v1/tasks/" + second + "/assign"), etag)
                .content("{\"similarReason\":\"Khác phạm vi báo cáo\"}"));
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    assertThat(note(UUID.fromString(second))).isEqualTo("Khác phạm vi báo cáo");
  }

  @Test
  void khongGanGiongThiGiaoBinhThuongVaCanhBaoChuaKiemDuoc() throws Exception {
    ObjectNode d = draft("Tổ chức tập huấn nghiệp vụ văn thư");
    MvcResult check =
        perform(
            post("/api/v1/tasks/assign-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(d.toString()));
    assertThat(body(check).get("similar")).isEmpty();
    // SVC-16 không trả lời: cảnh báo "chưa kiểm được" nhưng vẫn giao được.
    assertThat(body(check).get("items").toString()).contains("Chưa kiểm được");
    assigned(d, null);
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

  private byte[] fingerprint(UUID id) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql("SELECT dup_fingerprint FROM task.task WHERE id = ?")
                .param(id)
                .query(byte[].class)
                .single());
  }

  private String note(UUID id) throws Exception {
    return inTask(
        jdbc ->
            jdbc.sql(
                    "SELECT reason FROM task.task_state_log WHERE task_id = ?"
                        + " AND transition = 'GAN_GIONG'")
                .param(id)
                .query(String.class)
                .single());
  }

  private UUID foreignTask(String title) throws Exception {
    UUID id = UUID.randomUUID();
    inTask(
        jdbc ->
            jdbc.sql(
                    "INSERT INTO task.task (id, created_by, updated_by, period_id, period_bucket,"
                        + " code, title, task_group, source, assigner_id, org_unit_id, due_date,"
                        + " unit, dup_fingerprint, state_machine_version, status) VALUES (?, ?, ?,"
                        + " ?, ?, ?, ?, 'THUONG_XUYEN', 'DOT_XUAT', ?, ?, DATE '2027-12-15',"
                        + " 'bản', ?, 'mac-dinh@2.1.0', 'DA_GIAO')")
                .params(
                    id,
                    ASSIGNER,
                    ASSIGNER,
                    period,
                    BUCKET,
                    "NV-X-" + id.toString().substring(0, 8),
                    title,
                    ASSIGNER,
                    UUID.randomUUID(),
                    new byte[] {9})
                .update());
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
    d.put("dueDate", "2027-12-15");
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
