package vn.viettel.okrkpi.workflow.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.application.OpenApiConformanceEngine;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.infrastructure.batch.WorkflowBatchJobSource;
import vn.viettel.okrkpi.workflow.support.WorkflowIntegrationTest;
import vn.viettel.okrkpi.workflow.support.WorkflowTestDatabase;

/**
 * Kiểm thử tích hợp DV-06-02 trên PostgreSQL 17 thật: bảng chiếu cập nhật cùng giao dịch engine,
 * RLS theo tenant, hộp việc phân trang con trỏ, hoàn thành có Idempotency-Key và If-Match, tách vai
 * trò, duyệt hàng loạt 202 + Job, lịch sử theo khoá nghiệp vụ, và đối chiếu hợp đồng {@code
 * platform.yaml} (openapi-diff + lược đồ phản hồi).
 */
class WorkflowApiIT extends WorkflowIntegrationTest {

  private static final List<String> OPERATIONS =
      List.of(
          "listWorkflowInbox",
          "completeWorkflowTask",
          "batchCompleteWorkflowTasks",
          "getWorkflowHistory",
          "startPeriodCloseSaga",
          // Thư viện và vòng đời mẫu luồng nội bộ SCR-1505 (DV-06-09).
          "listWorkflowTemplates",
          "createWorkflowTemplate",
          "getWorkflowTemplate",
          "updateWorkflowTemplate",
          "runWorkflowTemplateTests",
          "submitWorkflowTemplate",
          "approveWorkflowTemplate",
          "listWorkflowTemplateRollouts");

  @Autowired private ObjectMapper json;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate appJdbc;
  @Autowired private WorkflowBatchJobSource jobSource;

  private final JdbcTemplate owner = WorkflowTestDatabase.owner();

  private final UUID tenant = UUID.randomUUID();
  private final UUID otherTenant = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private final UUID me = UUID.randomUUID();
  private final UUID author = UUID.randomUUID();
  private final UUID colleague = UUID.randomUUID();

  private String leaderGroup() {
    return tenant + ":leader:" + unit;
  }

  private Start assignedTo(UUID tenantId, UUID assignee, Instant due) {
    return new Start(
        tenantId,
        "OKR_OBJECTIVE",
        UUID.randomUUID(),
        author,
        assignee,
        null,
        colleague,
        null,
        due,
        null);
  }

  private int projectionCount(String businessId) {
    return owner.queryForObject(
        "SELECT count(*) FROM flowable.wf_task_projection WHERE business_id = ?::uuid",
        Integer.class,
        businessId);
  }

  // ------------------------------------------------------------------ bảng chiếu

  @Test
  void bangChieuGhiCungGiaoDichEngineVaHuyKhiGiaoDichHuy() {
    UUID rolledBack = UUID.randomUUID();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(
        status -> {
          start(
              new Start(
                  tenant, "PLAN", rolledBack, author, me, null, colleague, null, null, "Kế hoạch"));
          assertThat(
                  appJdbc.queryForObject(
                      "SELECT count(*) FROM flowable.wf_task_projection WHERE business_id = ?",
                      Integer.class,
                      rolledBack))
              .isEqualTo(1);
          status.setRollbackOnly();
        });
    assertThat(projectionCount(rolledBack.toString())).isZero();

    UUID period = UUID.randomUUID();
    Instant due = Instant.parse("2027-01-08T10:00:00Z");
    ProcessInstance instance =
        start(
            new Start(
                tenant,
                "PLAN",
                UUID.randomUUID(),
                author,
                null,
                leaderGroup(),
                colleague,
                period,
                due,
                "Phê duyệt kế hoạch Quý I/2027 của Ban Tổ chức"));
    Map<String, Object> row =
        owner.queryForMap(
            "SELECT * FROM flowable.wf_task_projection WHERE task_id = ?",
            openTask(instance).getId());
    assertThat(row.get("tenant_id")).isEqualTo(tenant);
    assertThat(row.get("assignee_id")).isNull();
    assertThat(row.get("candidate_role")).isEqualTo("leader");
    assertThat(row.get("candidate_unit_id")).isEqualTo(unit);
    assertThat(row.get("business_type")).isEqualTo("PLAN");
    assertThat(row.get("template_code")).isEqualTo(PROCESS_KEY);
    assertThat(row.get("period_id")).isEqualTo(period);
    assertThat(row.get("title")).isEqualTo("Phê duyệt kế hoạch Quý I/2027 của Ban Tổ chức");
    assertThat(row.get("row_version")).isEqualTo(1);

    // Nhận việc qua engine: bảng chiếu đổi người được giao và phiên bản cùng REV_.
    Task task = openTask(instance);
    tasks.claim(task.getId(), me.toString());
    Map<String, Object> claimed =
        owner.queryForMap(
            "SELECT p.assignee_id, p.row_version, t.rev_ FROM flowable.wf_task_projection p"
                + " JOIN flowable.act_ru_task t ON t.id_ = p.task_id WHERE p.task_id = ?",
            task.getId());
    assertThat(claimed.get("assignee_id")).isEqualTo(me);
    assertThat(claimed.get("row_version")).isEqualTo(claimed.get("rev_"));
  }

  @Test
  void rlsChanDocBangChieuCuaTenantKhac() throws Exception {
    start(assignedTo(otherTenant, me, null));
    start(assignedTo(tenant, me, null));
    Integer visible =
        TenantContext.call(
            TenantId.of(tenant),
            () ->
                new TransactionTemplate(transactionManager)
                    .execute(
                        s ->
                            appJdbc.queryForObject(
                                "SELECT count(*) FROM flowable.wf_task_projection"
                                    + " WHERE tenant_id = ?",
                                Integer.class,
                                otherTenant)));
    assertThat(visible).isZero();
    assertThat(
            owner.queryForObject(
                "SELECT count(*) FROM flowable.wf_task_projection WHERE tenant_id = ?",
                Integer.class,
                otherTenant))
        .isPositive();
  }

  // ------------------------------------------------------------------ hộp việc

  @Test
  void hopViecChiCoViecCuaToiTheoHanVaPhanTrangConTro() throws Exception {
    Instant now = Instant.now();
    start(assignedTo(tenant, me, now.plus(Duration.ofDays(2))));
    start(
        new Start(
            tenant,
            "PLAN",
            UUID.randomUUID(),
            author,
            null,
            leaderGroup(),
            colleague,
            null,
            now.minus(Duration.ofHours(1)),
            "Duyệt kế hoạch"));
    start(
        new Start(
            tenant,
            "PLAN",
            UUID.randomUUID(),
            author,
            null,
            tenant + ":leader:" + UUID.randomUUID(),
            colleague,
            null,
            null,
            null));
    start(assignedTo(tenant, colleague, null));
    start(assignedTo(otherTenant, me, null));

    MvcResult first =
        mvc.perform(get("/api/v1/workflow/inbox?limit=1").with(user(tenant, me, unit, "leader")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].businessType").value("PLAN"))
            .andExpect(jsonPath("$.items[0].overdue").value(true))
            .andExpect(jsonPath("$.items[0].candidateRole").value("leader"))
            .andExpect(jsonPath("$.counts.total").value(2))
            .andExpect(jsonPath("$.counts.overdue").value(1))
            .andReturn();
    assertResponseConforms(first, "/workflow/inbox", PathItem.HttpMethod.GET);
    String cursor = body(first).path("nextCursor").asText();
    assertThat(cursor).isNotBlank();

    MvcResult second =
        mvc.perform(
                get("/api/v1/workflow/inbox")
                    .param("limit", "1")
                    .param("cursor", cursor)
                    .with(user(tenant, me, unit, "leader")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].businessType").value("OKR_OBJECTIVE"))
            .andExpect(jsonPath("$.items[0].assigneeId").value(me.toString()))
            .andExpect(jsonPath("$.nextCursor").isEmpty())
            .andReturn();
    assertResponseConforms(second, "/workflow/inbox", PathItem.HttpMethod.GET);

    mvc.perform(
            get("/api/v1/workflow/inbox")
                .param("businessType", "OKR_OBJECTIVE")
                .with(user(tenant, me, unit, "leader")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));
    mvc.perform(get("/api/v1/workflow/inbox?limit=201").with(user(tenant, me, unit, "leader")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("COMMON-422-VALIDATION"));
    mvc.perform(get("/api/v1/workflow/inbox?cursor=gia-mao").with(user(tenant, me, unit, "leader")))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/workflow/inbox")).andExpect(status().isUnauthorized());
  }

  // ------------------------------------------------------------------ hoàn thành

  private org.springframework.test.web.servlet.ResultActions complete(
      String taskId, String ifMatch, String key, String decision, String comment, UUID caller)
      throws Exception {
    var request =
        post("/api/v1/workflow/tasks/{taskId}/complete", taskId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    comment == null
                        ? Map.of("decision", decision)
                        : Map.of("decision", decision, "comment", comment)))
            .with(user(tenant, caller, unit, "leader"));
    if (ifMatch != null) {
      request.header("If-Match", ifMatch);
    }
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return mvc.perform(request);
  }

  @Test
  void hoanThanhUngVienKiemDieuKienVaChongGhiTrung() throws Exception {
    ProcessInstance instance =
        start(
            new Start(
                tenant,
                "OKR_OBJECTIVE",
                UUID.randomUUID(),
                author,
                null,
                leaderGroup(),
                colleague,
                null,
                null,
                null));
    String taskId = openTask(instance).getId();

    complete(taskId, null, UUID.randomUUID().toString(), "DUYET", null, me)
        .andExpect(status().is(428));
    complete(taskId, "\"9\"", UUID.randomUUID().toString(), "DUYET", null, me)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WORKFLOW-409-VERSION-CONFLICT"));
    complete(taskId, "\"1\"", UUID.randomUUID().toString(), "TRA_LAI", null, me)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("COMMON-422-VALIDATION"))
        .andExpect(jsonPath("$.msg").value("MSG-02-10"));
    MvcResult level1 =
        complete(taskId, "\"1\"", UUID.randomUUID().toString(), "DUYET", null, UUID.randomUUID())
            .andExpect(status().isOk())
            .andReturn();
    // Người khác cùng vai trò, cùng đơn vị cũng là ứng viên hợp lệ: đã nhận và hoàn thành cấp 1.
    assertThat(body(level1).path("processEnded").asBoolean()).isFalse();

    // Cấp 2 giao cho đồng nghiệp: người gọi khác không được hoàn thành.
    String level2 = openTask(instance).getId();
    complete(level2, "\"1\"", UUID.randomUUID().toString(), "DUYET", null, me)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("COMMON-403-OUT-OF-SCOPE"));

    String key = UUID.randomUUID().toString();
    MvcResult done =
        complete(level2, "\"1\"", key, "DUYET", "Đồng ý.", colleague)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("DUYET"))
            .andExpect(jsonPath("$.processEnded").value(true))
            .andReturn();
    assertResponseConforms(done, "/workflow/tasks/{taskId}/complete", PathItem.HttpMethod.POST);
    assertThat(projectionCount(instance.getBusinessKey().split(":")[1])).isZero();

    // Gửi lại cùng khoá: trả lại phản hồi lần đầu; khoá mới: việc đã xử lý.
    MvcResult replay =
        complete(level2, "\"1\"", key, "DUYET", "Đồng ý.", colleague)
            .andExpect(status().isOk())
            .andReturn();
    assertThat(body(replay)).isEqualTo(body(done));
    complete(level2, "\"1\"", UUID.randomUUID().toString(), "DUYET", null, colleague)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WORKFLOW-409-TASK-COMPLETED"));
    complete("khong-ton-tai", "\"1\"", UUID.randomUUID().toString(), "DUYET", null, colleague)
        .andExpect(status().isNotFound());
  }

  @Test
  void nguoiKhoiTaoKhongTuDuyetVaKhongXuLyViecTenantKhac() throws Exception {
    ProcessInstance own =
        start(
            new Start(
                tenant,
                "OKR_OBJECTIVE",
                UUID.randomUUID(),
                me,
                me,
                null,
                colleague,
                null,
                null,
                null));
    MvcResult denied =
        complete(openTask(own).getId(), "\"1\"", UUID.randomUUID().toString(), "DUYET", null, me)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("WORKFLOW-403-SELF-APPROVAL"))
            .andExpect(jsonPath("$.msg").value("MSG-02-11"))
            .andReturn();
    assertResponseConforms(denied, "/workflow/tasks/{taskId}/complete", PathItem.HttpMethod.POST);
    assertThat(openTask(own)).isNotNull();

    ProcessInstance foreign = start(assignedTo(otherTenant, me, null));
    complete(openTask(foreign).getId(), "\"1\"", UUID.randomUUID().toString(), "DUYET", null, me)
        .andExpect(status().isNotFound());
  }

  // ------------------------------------------------------------------ duyệt hàng loạt

  @Test
  void duyetHangLoatTraJobVaKetQuaTungMuc() throws Exception {
    List<Map<String, Object>> tooMany = new ArrayList<>();
    for (int i = 0; i < 201; i++) {
      tooMany.add(Map.of("taskId", "t" + i, "rowVersion", 1));
    }
    MvcResult tooLarge =
        mvc.perform(
                post("/api/v1/workflow/tasks/batch-complete")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("decision", "DUYET", "items", tooMany)))
                    .with(user(tenant, me, unit, "leader")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("WORKFLOW-422-BATCH-TOO-LARGE"))
            .andReturn();
    assertResponseConforms(tooLarge, "/workflow/tasks/batch-complete", PathItem.HttpMethod.POST);

    String a = openTask(start(assignedTo(tenant, me, null))).getId();
    String b = openTask(start(assignedTo(tenant, me, null))).getId();
    String c = openTask(start(assignedTo(tenant, me, null))).getId();
    MvcResult accepted =
        mvc.perform(
                post("/api/v1/workflow/tasks/batch-complete")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "decision",
                                "DUYET",
                                "comment",
                                "Đồng ý.",
                                "items",
                                List.of(
                                    Map.of("taskId", a, "rowVersion", 1),
                                    Map.of("taskId", b, "rowVersion", 7),
                                    Map.of("taskId", c, "rowVersion", 1)))))
                    .with(user(tenant, me, unit, "leader")))
            .andExpect(status().isAccepted())
            .andExpect(header().exists("Location"))
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.type").value("WORKFLOW_BATCH_COMPLETE"))
            .andReturn();
    assertResponseConforms(accepted, "/workflow/tasks/batch-complete", PathItem.HttpMethod.POST);
    String jobId = body(accepted).path("id").asText();
    assertThat(accepted.getResponse().getHeader("Location")).isEqualTo("/api/v1/jobs/" + jobId);

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> job(jobId).job().status() == JobStatus.HOAN_TAT);
    TrackedJob tracked = job(jobId);
    assertThat(tracked.initiator().value()).isEqualTo(me);
    assertThat(tracked.job().progress()).isEqualTo(100);
    List<Map<String, Object>> results =
        owner.query(
            "SELECT e->>'id' AS id, (e->>'status')::int AS status, e->>'code' AS code"
                + " FROM flowable.wf_task_batch b, jsonb_array_elements(b.results_json) e"
                + " WHERE b.id = ?::uuid",
            (rs, n) ->
                Map.of(
                    "id", rs.getString("id"),
                    "status", rs.getInt("status"),
                    "code", String.valueOf(rs.getString("code"))),
            jobId);
    assertThat(results).extracting(r -> r.get("status")).containsExactly(200, 409, 200);
    assertThat(results.get(1).get("code")).isEqualTo("WORKFLOW-409-VERSION-CONFLICT");
    assertThat(tasks.createTaskQuery().taskId(b).count()).isEqualTo(1);
    assertThat(tasks.createTaskQuery().taskId(a).count()).isZero();
  }

  private TrackedJob job(String jobId) throws Exception {
    return TenantContext.call(
        TenantId.of(tenant), () -> jobSource.find(TenantId.of(tenant), jobId).orElseThrow());
  }

  // ------------------------------------------------------------------ lịch sử

  @Test
  void lichSuTheoKhoaNghiepVu() throws Exception {
    ProcessInstance instance = start(assignedTo(tenant, me, null));
    complete(
            openTask(instance).getId(),
            "\"1\"",
            UUID.randomUUID().toString(),
            "DUYET",
            "Đồng ý.",
            me)
        .andExpect(status().isOk());
    MvcResult running =
        mvc.perform(
                get("/api/v1/workflow/instances/{key}/history", instance.getBusinessKey())
                    .with(user(tenant, me, unit, "leader")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DANG_CHAY"))
            .andExpect(jsonPath("$.templateCode").value(PROCESS_KEY))
            .andExpect(jsonPath("$.entries[0].action").value("TAO_TIEN_TRINH"))
            .andExpect(jsonPath("$.entries[0].actorId").value(author.toString()))
            .andExpect(jsonPath("$.entries[1].action").value("DUYET"))
            .andExpect(jsonPath("$.entries[1].actorId").value(me.toString()))
            .andExpect(jsonPath("$.entries[1].comment").value("Đồng ý."))
            .andReturn();
    assertResponseConforms(
        running, "/workflow/instances/{businessKey}/history", PathItem.HttpMethod.GET);

    complete(
            openTask(instance).getId(),
            "\"1\"",
            UUID.randomUUID().toString(),
            "DUYET",
            null,
            colleague)
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/workflow/instances/{key}/history", instance.getBusinessKey())
                .with(user(tenant, me, unit, "leader")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DA_KET_THUC"))
        .andExpect(jsonPath("$.entries.length()").value(4))
        .andExpect(jsonPath("$.entries[3].action").value("KET_THUC"));

    mvc.perform(
            get("/api/v1/workflow/instances/{key}/history", instance.getBusinessKey())
                .with(user(otherTenant, me, unit, "leader")))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/workflow/instances/{key}/history", "sai-dang")
                .with(user(tenant, me, unit, "leader")))
        .andExpect(status().isNotFound());
  }

  // ------------------------------------------------------------------ hợp đồng

  @Test
  void openApiThucTeKhopHopDongChoCacThaoTacDaHienThuc() throws Exception {
    String actualJson =
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    // Lưu tài liệu thực tế để tra khi kiểm thử đỏ.
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("target", "workflow-actual-openapi.json"), actualJson);
    OpenAPI expected = OpenApiConformanceEngine.loadExpectedSpec(ContractModule.PLATFORM);
    Paths kept = new Paths();
    expected
        .getPaths()
        .forEach(
            (path, item) -> {
              PathItem filtered = new PathItem();
              filtered.setParameters(item.getParameters());
              item.readOperationsMap()
                  .forEach(
                      (method, op) -> {
                        if (OPERATIONS.contains(op.getOperationId())) {
                          filtered.operation(method, op);
                        }
                      });
              if (!filtered.readOperations().isEmpty()) {
                kept.addPathItem(path, filtered);
              }
            });
    expected.setPaths(kept);
    assertThat(kept).hasSize(11);
    OpenAPI actual = OpenApiConformanceEngine.parseSpecContent(actualJson);
    // GET /jobs/{id} thuộc hợp đồng jobs.yaml (mô-đun khác), không so ở đây.
    actual.getPaths().keySet().removeIf(k -> k.endsWith("/jobs/{id}"));
    OpenApiConformanceEngine.verifyConformance(
            ContractModule.PLATFORM, ownerServiceCode(), expected, actual)
        .assertCompliant();
    assertThat(Set.copyOf(OPERATIONS)).hasSize(13);
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
