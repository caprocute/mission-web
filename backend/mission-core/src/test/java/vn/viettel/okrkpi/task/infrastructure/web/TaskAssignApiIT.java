package vn.viettel.okrkpi.task.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
 * Soạn và giao nhiệm vụ (CORE-02-14) trên PostgreSQL thật: lưu nháp, sửa nháp, thiếu căn cứ thì 422
 * kèm {@code rule}, giao xong ghi EVT-04 và EVT-21, nhiệm vụ đã giao không sửa hay huỷ nháp được,
 * giao đồng loạt mỗi đơn vị một nhiệm vụ.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TaskAssignApiIT.TaskAssignTestConfig.class)
class TaskAssignApiIT extends ContractConformanceTest {

  static final UUID ASSIGNER = TestTaskCallers.DEFAULT_PERSON;
  static final UUID LEAD = UUID.fromString("01927a02-0000-7000-8000-00000000c214");
  static final UUID UNIT = UUID.fromString("01927a02-0000-7000-8000-00000000d214");
  static final UUID HEAD = UUID.fromString("01927a02-0000-7000-8000-00000000e214");
  static final int BUCKET = 20273;

  /** Outbox với bộ tuần tự giả và bản sao tổ chức cố định: mọi người công tác tại {@link #UNIT}. */
  static class TaskAssignTestConfig {

    @Bean
    @Primary
    TaskAssignedEvents assignTestAssignedEvents(
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
    OrgDirectoryApi assignTestOrgDirectory() {
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
                                    + " updated_by) VALUES (?, 'Q3-2027-GV', 'Quý III năm 2027',"
                                    + " 'QUY', 'NHA_NUOC', DATE '2027-07-01', DATE '2027-09-30',"
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
  void soanSuaGiaoVaKhongSuaDuocSauKhiGiao() throws Exception {
    ObjectNode draft = draft("Rà soát quy chế làm việc của đơn vị");
    draft.remove("basis");
    MvcResult created = perform(write(post("/api/v1/tasks"), null).content(draft.toString()));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    JsonNode saved = body(created);
    String id = saved.get("id").asText();
    assertThat(created.getResponse().getHeader("Location")).endsWith("/api/v1/tasks/" + id);
    assertThat(saved.get("code").asText()).matches("NV-2027-Q3-\\d{4}");
    String etag = created.getResponse().getHeader("ETag");

    MvcResult form = perform(get("/api/v1/tasks/" + id + "/draft"));
    assertThat(form.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(form).get("period").get("id").asText()).isEqualTo(period.toString());

    // Thiếu căn cứ cho việc phát sinh: 422 có rule BR-05-09.
    MvcResult blocked = perform(write(post("/api/v1/tasks/" + id + "/assign"), etag));
    assertThat(blocked.getResponse().getStatus()).isEqualTo(422);
    assertThat(blocked.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .contains("BR-05-09");

    MvcResult check =
        perform(
            post("/api/v1/tasks/assign-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(draft.toString()));
    assertThat(check.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(check).get("items").toString()).contains("BR-05-09");

    draft.put("basis", "Kết luận giao ban tháng 7");
    MvcResult edited = perform(write(put("/api/v1/tasks/" + id), etag).content(draft.toString()));
    assertThat(edited.getResponse().getStatus()).isEqualTo(200);
    etag = edited.getResponse().getHeader("ETag");

    MvcResult assigned = perform(write(post("/api/v1/tasks/" + id + "/assign"), etag));
    assertThat(assigned.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(assigned).get("status").asText()).isEqualTo("DA_GIAO");
    assertThat(outbox(UUID.fromString(id)))
        .contains("task.task.assigned.v1", "task.task.statuschanged.v1");
    String after = assigned.getResponse().getHeader("ETag");

    assertThat(
            perform(write(put("/api/v1/tasks/" + id), after).content(draft.toString()))
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8))
        .contains("TASK-409-NOT-DRAFT");
    assertThat(perform(write(delete("/api/v1/tasks/" + id), after)).getResponse().getStatus())
        .isEqualTo(409);
    assertThat(perform(get("/api/v1/tasks/" + id + "/draft")).getResponse().getStatus())
        .isEqualTo(404);

    MvcResult detail = perform(get("/api/v1/tasks/" + id));
    assertThat(detail.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(detail).get("status").asText()).isEqualTo("DA_GIAO");
    MvcResult people = perform(get("/api/v1/tasks/" + id + "/assignees"));
    assertThat(body(people).get("items").get(0).get("id").asText()).isEqualTo(LEAD.toString());

    MvcResult unit =
        perform(
            get("/api/v1/tasks").param("unit", UNIT.toString()).param("period", period.toString()));
    assertThat(unit.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(unit).get("items").toString()).contains(id);
  }

  @Test
  void huyBanNhapVaKhungSoanMoi() throws Exception {
    MvcResult created =
        perform(
            write(post("/api/v1/tasks"), null).content(draft("Lập danh sách hồ sơ").toString()));
    String id = body(created).get("id").asText();
    MvcResult removed =
        perform(write(delete("/api/v1/tasks/" + id), created.getResponse().getHeader("ETag")));
    assertThat(removed.getResponse().getStatus()).isEqualTo(204);
    assertThat(perform(get("/api/v1/tasks/" + id + "/draft")).getResponse().getStatus())
        .isEqualTo(404);
  }

  @Test
  void giaoDongLoatMoiDonViMotNhiemVu() throws Exception {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    ObjectNode d = draft("Tổng hợp báo cáo an toàn lao động");
    d.remove("leadPersonId");
    ObjectNode req = json.createObjectNode();
    req.set("draft", d);
    req.set("unitIds", json.valueToTree(List.of(u1, u2)));
    MvcResult accepted =
        perform(write(post("/api/v1/tasks/bulk-assign"), null).content(req.toString()));
    assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
    assertThat(accepted.getResponse().getHeader("Location")).startsWith("/api/v1/jobs/");
    long n = 0;
    for (int i = 0; i < 100 && n < 2; i++) {
      Thread.sleep(100);
      n =
          inTask(
              jdbc ->
                  jdbc.sql(
                          "SELECT count(*) FROM task.task t JOIN task.task_assignee a ON a.task_id"
                              + " = t.id AND a.period_bucket = t.period_bucket WHERE a.role ="
                              + " 'CHU_TRI' AND a.org_unit_id IN (?, ?) AND t.status = 'DA_GIAO'")
                      .params(u1, u2)
                      .query(Long.class)
                      .single());
    }
    assertThat(n).isEqualTo(2);
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
    d.put("dueDate", "2027-09-15");
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
