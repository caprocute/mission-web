package vn.viettel.okrkpi.workflow.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.flowable.engine.ManagementService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.application.approval.StartApprovalCommand;
import vn.viettel.okrkpi.workflow.application.approval.StartApprovalService;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;
import vn.viettel.okrkpi.workflow.support.WorkflowIntegrationTest;
import vn.viettel.okrkpi.workflow.support.WorkflowTestDatabase;

/**
 * Kiểm thử triển khai ba mẫu luồng duyệt phần 1 trên PostgreSQL 17 thật (Flyway V1..V4, RLS, tài
 * khoản {@code svc_flowable}): mẫu triển khai lúc khởi động không gắn tenant và số định nghĩa không
 * tăng khi ba tenant dùng; phân giải cấp duyệt bằng {@code org_replica}, tham số từ {@code
 * wf_binding}; khởi tạo idempotent dưới khoá tư vấn khi hai yêu cầu đồng thời; hộp việc và hoàn
 * thành qua API của DV-06-02 dẫn tới bước external worker gọi lại lõi.
 */
class ApprovalTemplatesIT extends WorkflowIntegrationTest {

  private static final Set<String> TEMPLATE_KEYS =
      Set.of("duyet-okr", "duyet-ke-hoach-quy", "duyet-de-nghi");

  @Autowired private StartApprovalService starter;
  @Autowired private ManagementService management;
  @Autowired private ObjectMapper json;

  private final JdbcTemplate owner = WorkflowTestDatabase.owner();

  /** Cây mẫu của một tenant trong bản sao tổ chức. */
  record Tree(UUID tenant, UUID dangUy, UUID chiBo, UUID coQuan, UUID banA) {}

  private Tree tree() {
    Tree t =
        new Tree(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID());
    unit(t.tenant(), t.dangUy(), "TO_CHUC_DANG", null, null);
    unit(t.tenant(), t.chiBo(), "CHI_BO", t.dangUy(), null);
    unit(t.tenant(), t.coQuan(), "CO_QUAN", null, null);
    unit(t.tenant(), t.banA(), "PHONG_BAN", t.chiBo(), t.coQuan());
    return t;
  }

  private void unit(UUID tenant, UUID id, String type, UUID partyParent, UUID adminParent) {
    owner.update(
        "INSERT INTO org_replica.org_unit_v (tenant_id, id, valid_from, code, name, unit_type,"
            + " party_parent_id, admin_parent_id, source_version, source_event_id)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
        tenant,
        id,
        Date.valueOf(LocalDate.of(2020, 1, 1)),
        "DV-" + id.toString().substring(0, 8),
        "Đơn vị kiểm thử",
        type,
        partyParent,
        adminParent,
        UUID.randomUUID());
  }

  private StartApprovalCommand command(
      Tree t, ApprovalTemplate template, UUID businessId, long version) {
    return new StartApprovalCommand(
        t.tenant(),
        template,
        businessId,
        version,
        ApprovalFlow.DANG,
        t.banA(),
        false,
        UUID.randomUUID(),
        null,
        "Phê duyệt Quý IV/2026",
        null,
        3,
        UUID.randomUUID(),
        "CHU_QUAN");
  }

  private long templateDefinitions() {
    return repository.createProcessDefinitionQuery().list().stream()
        .filter(d -> TEMPLATE_KEYS.contains(d.getKey()))
        .count();
  }

  @Test
  void baTenantDungChungBaDinhNghiaKhongGanTenant() {
    assertThat(
            repository.createProcessDefinitionQuery().list().stream()
                .filter(d -> TEMPLATE_KEYS.contains(d.getKey()))
                .map(d -> d.getTenantId()))
        .hasSize(3)
        .allSatisfy(tenantId -> assertThat(tenantId).isEmpty());
    long before = templateDefinitions();

    List<Tree> tenants = List.of(tree(), tree(), tree());
    for (Tree t : tenants) {
      for (ApprovalTemplate template : ApprovalTemplate.values()) {
        StartApprovalService.Result r = starter.start(command(t, template, UUID.randomUUID(), 1));
        ProcessInstance instance =
            runtime
                .createProcessInstanceQuery()
                .processInstanceId(r.processInstanceId())
                .singleResult();
        assertThat(instance.getTenantId()).isEqualTo(t.tenant().toString());
        assertThat(instance.getProcessDefinitionKey()).isEqualTo(template.code());
      }
    }
    assertThat(templateDefinitions()).isEqualTo(before).isEqualTo(3);

    // Hộp việc: mỗi tenant có dòng chiếu với vai trò, đơn vị đã phân giải từ org_replica.
    for (Tree t : tenants) {
      List<Map<String, Object>> rows =
          owner.queryForList(
              "SELECT template_code, candidate_role, candidate_unit_id, business_type"
                  + " FROM flowable.wf_task_projection WHERE tenant_id = ? ORDER BY template_code",
              t.tenant());
      assertThat(rows).hasSize(3);
      Map<String, Object> okr =
          rows.stream()
              .filter(r -> "duyet-okr".equals(r.get("template_code")))
              .findFirst()
              .orElseThrow();
      assertThat(okr.get("candidate_role")).isEqualTo("leader");
      assertThat(okr.get("candidate_unit_id")).isEqualTo(t.chiBo());
      assertThat(okr.get("business_type")).isEqualTo("OKR_OBJECTIVE");
    }
  }

  @Test
  void thamSoTuWfBindingVaCapDaPhanGiai() throws Exception {
    Tree t = tree();
    UUID templateRow = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    owner.update(
        "INSERT INTO flowable.wf_template (id, tenant_id, template_code, version_no, bpmn_xml,"
            + " bpmn_sha256, status, created_by, updated_by)"
            + " VALUES (?, ?, 'duyet-ke-hoach-quy', 1, '<definitions/>', ?, 'HIEU_LUC', ?, ?)",
        templateRow,
        t.tenant(),
        new byte[32],
        actor,
        actor);
    owner.update(
        "INSERT INTO flowable.wf_binding (id, tenant_id, process_kind, flow, wf_template_id,"
            + " params_json, created_by, updated_by) VALUES (?, ?, 'KE_HOACH_QUY', 'DANG', ?, ?::jsonb, ?, ?)",
        UUID.randomUUID(),
        t.tenant(),
        templateRow,
        "{\"remindAfter\":\"P1D\",\"allowDelegation\":false,\"signMethod\":\"USB_TOKEN\","
            + "\"thoi_han_moi_buoc\":\"P4D_LV\",\"cap\":["
            + "{\"vai_tro\":\"manager\",\"pham_vi\":\"DON_VI_TRUC_TIEP\",\"han_ngay_lam_viec\":2},"
            + "{\"vai_tro\":\"leader\",\"pham_vi\":\"CAP_UY_CO_THAM_QUYEN\"}]}",
        actor,
        actor);
    owner.update(
        "INSERT INTO flowable.wf_holiday_calendar (tenant_id, calendar_date, day_kind) VALUES (?, ?, 'NGHI_LE')",
        t.tenant(),
        Date.valueOf(LocalDate.now().plusDays(1)));

    String pid =
        starter
            .start(command(t, ApprovalTemplate.DUYET_KE_HOACH_QUY, UUID.randomUUID(), 5))
            .processInstanceId();

    Map<String, Object> vars = runtime.getVariables(pid);
    assertThat(vars)
        .containsEntry(ApprovalVariables.REMIND_AFTER, "P1D")
        .containsEntry(ApprovalVariables.CHO_PHEP_UY_QUYEN, false)
        .containsEntry(ApprovalVariables.PHUONG_THUC_KY, "USB_TOKEN")
        .containsEntry(ApprovalVariables.KE_HOACH_PHIEN_BAN, 3);
    JsonNode levels = json.valueToTree(vars.get(ApprovalVariables.APPROVAL_LEVELS));
    assertThat(levels).hasSize(2);
    assertThat(levels.get(0).get("orgUnitId").asText()).isEqualTo(t.banA().toString());
    assertThat(levels.get(0).get("hanNgayLamViec").asInt()).isEqualTo(2);
    assertThat(levels.get(1).get("orgUnitId").asText()).isEqualTo(t.dangUy().toString());
    assertThat(levels.get(1).get("hanNgayLamViec").asInt()).isEqualTo(4);
    Task task = tasks.createTaskQuery().processInstanceId(pid).singleResult();
    assertThat(tasks.getIdentityLinksForTask(task.getId()))
        .anySatisfy(l -> assertThat(l.getGroupId()).isEqualTo(t.tenant() + ":manager:" + t.banA()));
    assertThat(ApprovalVariables.sizeOf(vars)).isLessThan(ApprovalVariables.MAX_TOTAL_BYTES);
  }

  @Test
  void khoiTaoDongThoiCungDoiTuongChiTaoMotTienTrinh() {
    Tree t = tree();
    UUID businessId = UUID.randomUUID();
    List<CompletableFuture<StartApprovalService.Result>> futures = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      futures.add(
          CompletableFuture.supplyAsync(
              () -> starter.start(command(t, ApprovalTemplate.DUYET_OKR, businessId, 2))));
    }
    List<StartApprovalService.Result> results =
        futures.stream().map(CompletableFuture::join).toList();
    assertThat(results.stream().map(StartApprovalService.Result::processInstanceId).distinct())
        .hasSize(1);
    assertThat(results.stream().filter(StartApprovalService.Result::created)).hasSize(1);
    assertThat(
            runtime
                .createProcessInstanceQuery()
                .processInstanceBusinessKey("OKR_OBJECTIVE:" + businessId)
                .count())
        .isEqualTo(1);
  }

  @Test
  void khongPhanGiaiDuocDonViThiKhongDeLaiTienTrinh() {
    Tree t = tree();
    UUID businessId = UUID.randomUUID();
    StartApprovalCommand unknownUnit =
        new StartApprovalCommand(
            t.tenant(),
            ApprovalTemplate.DUYET_OKR,
            businessId,
            1,
            ApprovalFlow.DANG,
            UUID.randomUUID(),
            false,
            null,
            null,
            "Phê duyệt",
            null,
            null,
            null,
            null);
    assertThatThrownBy(() -> starter.start(unknownUnit)).isInstanceOf(ApprovalSetupException.class);
    assertThat(
            runtime
                .createProcessInstanceQuery()
                .processInstanceBusinessKey("OKR_OBJECTIVE:" + businessId)
                .count())
        .isZero();
  }

  @Test
  void hopViecVaHoanThanhQuaApiDanToiBuocGoiLaiLoi() throws Exception {
    Tree t = tree();
    UUID leader = UUID.randomUUID();
    String pid =
        starter
            .start(command(t, ApprovalTemplate.DUYET_OKR, UUID.randomUUID(), 1))
            .processInstanceId();

    mvc.perform(
            get("/api/v1/workflow/inbox?limit=10")
                .with(user(t.tenant(), leader, t.chiBo(), "leader")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].templateCode").value("duyet-okr"))
        .andExpect(jsonPath("$.items[0].candidateRole").value("leader"));

    Task task = tasks.createTaskQuery().processInstanceId(pid).singleResult();
    mvc.perform(
            post("/api/v1/workflow/tasks/{taskId}/complete", task.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"DUYET\",\"comment\":\"Đồng ý\"}")
                .header("If-Match", "\"1\"")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(user(t.tenant(), leader, t.chiBo(), "leader")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.processEnded").value(false));

    assertThat(management.createExternalWorkerJobQuery().processInstanceId(pid).list())
        .singleElement()
        .satisfies(
            j -> {
              assertThat(j.getElementId()).isEqualTo("applyApprove");
              assertThat(j.getTenantId()).isEqualTo(t.tenant().toString());
            });
  }
}
