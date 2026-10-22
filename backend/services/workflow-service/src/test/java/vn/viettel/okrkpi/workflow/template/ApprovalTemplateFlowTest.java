package vn.viettel.okrkpi.workflow.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.ManagementService;
import org.flowable.engine.task.Comment;
import org.flowable.job.api.AcquiredExternalWorkerJob;
import org.flowable.job.api.ExternalWorkerJob;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.application.approval.StartApprovalCommand;
import vn.viettel.okrkpi.workflow.application.approval.StartApprovalService;
import vn.viettel.okrkpi.workflow.application.port.CoreApprovalGateway;
import vn.viettel.okrkpi.workflow.domain.Decision;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalParams;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalRule;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalScope;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;
import vn.viettel.okrkpi.workflow.domain.approval.OrgUnit;
import vn.viettel.okrkpi.workflow.infrastructure.flowable.FlowableTaskReassignment;

/**
 * Kiểm thử Flowable cho một mẫu luồng duyệt phần 1 trên engine thật (H2): duyệt đủ cấp, trả lại ở
 * cấp giữa, quá hạn nhắc, đổi người duyệt khi điều động là uỷ quyền có lưu vết, cấp mặc định từ
 * DMN, khởi tạo idempotent và ngân sách biến. Lớp con chọn mẫu và kiểm trường riêng của lời gọi
 * lõi.
 */
abstract class ApprovalTemplateFlowTest {

  static final String WORKER = "kiem-thu-worker";

  ApprovalEngineHarness h;
  UUID tenant;
  ApprovalEngineHarness.Tree tree;
  UUID initiator;
  UUID taskId;

  /** Mẫu đang kiểm. */
  abstract ApprovalTemplate template();

  /**
   * Đơn vị và số ngày làm việc của cấp mặc định do DMN chọn (khối Đảng, không phải người đứng đầu).
   */
  abstract UUID defaultUnit(ApprovalEngineHarness.Tree tree);

  abstract int defaultWorkingDays();

  /** Kiểm trường riêng của mẫu trong lời gọi lõi. */
  abstract void assertTemplateFields(CoreApprovalGateway.Request request);

  @BeforeEach
  void setUp() {
    h = new ApprovalEngineHarness();
    tenant = UUID.randomUUID();
    tree = h.org.tree(tenant);
    initiator = UUID.randomUUID();
    taskId = UUID.randomUUID();
  }

  @AfterEach
  void tearDown() {
    h.close();
  }

  StartApprovalCommand command(UUID businessId, long version) {
    return new StartApprovalCommand(
        tenant,
        template(),
        businessId,
        version,
        ApprovalFlow.DANG,
        tree.banA(),
        false,
        initiator,
        UUID.randomUUID(),
        "Phê duyệt hồ sơ Quý IV/2026",
        "khoi-dang/qd366@1.0",
        2,
        taskId,
        "KHACH_QUAN");
  }

  /** Ba cấp tuỳ chỉnh của tenant (TL-02b SCR-1504): quản lý, cấp trên, cấp uỷ. */
  void threeLevels(boolean allowDelegation) {
    h.bindings.params.put(
        tenant,
        new ApprovalParams(
            "P2D",
            allowDelegation,
            List.of(
                new ApprovalRule(1, "manager", ApprovalScope.DON_VI_TRUC_TIEP, 2),
                new ApprovalRule(2, "leader", ApprovalScope.CAP_TREN_TRUC_TIEP, 3),
                new ApprovalRule(3, "leader", ApprovalScope.CAP_UY_CO_THAM_QUYEN, 3)),
            "USB_TOKEN"));
  }

  String group(String role, UUID unit) {
    return tenant + ":" + role + ":" + unit;
  }

  String start(UUID businessId) {
    StartApprovalService.Result r = h.starter.start(command(businessId, 7));
    assertThat(r.created()).isTrue();
    return r.processInstanceId();
  }

  List<ExternalWorkerJob> externalJobs(String pid) {
    return h.engine
        .getManagementService()
        .createExternalWorkerJobQuery()
        .processInstanceId(pid)
        .list();
  }

  @Test
  void duyetDuCapRoiGoiLaiLoiPheDuyet() {
    threeLevels(true);
    UUID businessId = UUID.randomUUID();
    String pid = start(businessId);
    List<String> expected =
        List.of(
            group("manager", tree.banA()),
            group("leader", tree.chiBo()),
            group("leader", tree.dangUy()));
    UUID lastActor = null;
    for (String candidate : expected) {
      Task task = h.openTask(pid);
      assertThat(h.candidateGroups(task)).containsExactly(candidate);
      assertThat(task.getTenantId()).isEqualTo(tenant.toString());
      assertThat(task.getName()).isEqualTo("Phê duyệt hồ sơ Quý IV/2026");
      lastActor = UUID.randomUUID();
      h.complete(tenant, task, lastActor, Decision.DUYET, null);
    }
    assertThat(h.openTask(pid)).isNull();
    assertThat(externalJobs(pid))
        .singleElement()
        .satisfies(j -> assertThat(j.getElementId()).isEqualTo("applyApprove"));

    assertThat(h.callbacks.runOnce(WORKER)).isEqualTo(1);

    assertThat(h.core.calls).hasSize(1);
    CoreApprovalGateway.Request call = h.core.calls.get(0);
    assertThat(call.template()).isEqualTo(template());
    assertThat(call.decision()).isEqualTo(Decision.DUYET);
    assertThat(call.businessId()).isEqualTo(businessId);
    assertThat(call.tenantId()).isEqualTo(tenant);
    assertThat(call.objectVersion()).isEqualTo(7);
    assertThat(call.deciderId()).isEqualTo(lastActor);
    assertThat(call.idempotencyKey())
        .isEqualTo(
            UUID.nameUUIDFromBytes(
                ("wf:" + pid + ":applyApprove").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertTemplateFields(call);
    assertThat(h.ended(pid)).isFalse();
    h.runAsyncJobs(pid);
    assertThat(h.ended(pid)).isTrue();
    assertThat(h.endActivity(pid)).isEqualTo("endApproved");
  }

  @Test
  void traLaiOCapGiuaKetThucSomVaGoiLaiLoiTraLai() {
    threeLevels(true);
    String pid = start(UUID.randomUUID());
    h.complete(tenant, h.openTask(pid), UUID.randomUUID(), Decision.DUYET, "Đồng ý");
    Task middle = h.openTask(pid);
    assertThat(h.candidateGroups(middle)).containsExactly(group("leader", tree.chiBo()));
    UUID returner = UUID.randomUUID();
    h.complete(tenant, middle, returner, Decision.TRA_LAI, "Đề nghị bổ sung căn cứ");

    assertThat(h.openTask(pid)).isNull();
    assertThat(externalJobs(pid))
        .singleElement()
        .satisfies(j -> assertThat(j.getElementId()).isEqualTo("applyReturn"));
    assertThat(
            h.engine
                .getHistoryService()
                .createHistoricTaskInstanceQuery()
                .processInstanceId(pid)
                .count())
        .isEqualTo(2);

    h.callbacks.runOnce(WORKER);
    CoreApprovalGateway.Request call = h.core.calls.get(0);
    assertThat(call.decision()).isEqualTo(Decision.TRA_LAI);
    assertThat(call.comment()).isEqualTo("Đề nghị bổ sung căn cứ");
    assertThat(call.deciderId()).isEqualTo(returner);
    h.runAsyncJobs(pid);
    assertThat(h.endActivity(pid)).isEqualTo("endReturned");
  }

  @Test
  void quaHanThiNhacKhongNgatViecDuyet() {
    String pid = start(UUID.randomUUID());
    Task task = h.openTask(pid);
    ManagementService management = h.engine.getManagementService();
    Job timer = management.createTimerJobQuery().processInstanceId(pid).singleResult();
    assertThat(timer).isNotNull();
    // Hạn nhắc theo remindAfter = P2D tính từ lúc khởi tạo (đồng hồ engine).
    assertThat(timer.getDuedate().toInstant())
        .isAfter(Instant.now().plusSeconds(2 * 86_400 - 120))
        .isBefore(Instant.now().plusSeconds(2 * 86_400 + 120));

    Job executable = management.moveTimerToExecutableJob(timer.getId());
    management.executeJob(executable.getId());

    List<ExternalWorkerJob> jobs = externalJobs(pid);
    assertThat(jobs)
        .singleElement()
        .satisfies(j -> assertThat(j.getElementId()).isEqualTo("notifyRemind"));
    assertThat(h.openTask(pid).getId()).isEqualTo(task.getId());
    // Worker gọi lại lõi không nhận chủ đề nhắc việc.
    assertThat(h.callbacks.runOnce(WORKER)).isZero();
    assertThat(h.core.calls).isEmpty();

    List<AcquiredExternalWorkerJob> remind =
        management
            .createExternalWorkerJobAcquireBuilder()
            .topic("notify-remind", java.time.Duration.ofMinutes(1))
            .acquireAndLock(5, "notification-service");
    assertThat(remind).hasSize(1);
    assertThat(remind.get(0).getTenantId()).isEqualTo(tenant.toString());
    management
        .createExternalWorkerCompletionBuilder(remind.get(0).getId(), "notification-service")
        .complete();
    h.runAsyncJobs(pid);

    assertThat(externalJobs(pid)).isEmpty();
    assertThat(h.openTask(pid).getId()).isEqualTo(task.getId());
    assertThat(h.ended(pid)).isFalse();
  }

  @Test
  void doiNguoiDuyetKhiDieuDongLaUyQuyenCoLuuVet() {
    threeLevels(true);
    String pid = start(UUID.randomUUID());
    Task first = h.openTask(pid);
    UUID substitute = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    boolean changed =
        h.transfers.transfer(
            new vn.viettel.okrkpi.workflow.application.approval.ApproverTransferService.Command(
                tenant,
                first.getId(),
                substitute,
                admin,
                "Trưởng ban được điều động sang đơn vị khác"));
    assertThat(changed).isTrue();
    Task reassigned = h.openTask(pid);
    assertThat(reassigned.getAssignee()).isEqualTo(substitute.toString());
    List<Comment> comments =
        h.engine
            .getTaskService()
            .getTaskComments(first.getId(), FlowableTaskReassignment.COMMENT_TYPE);
    assertThat(comments)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.getUserId()).isEqualTo(admin.toString());
              assertThat(c.getFullMessage()).contains(substitute.toString()).contains("điều động");
            });
    Map<String, Object> local = h.engine.getTaskService().getVariablesLocal(first.getId());
    assertThat(local)
        .containsEntry(
            FlowableTaskReassignment.REASON_VARIABLE, "Trưởng ban được điều động sang đơn vị khác");
    // Lặp lại cùng người thay không ghi thêm vết.
    assertThat(
            h.transfers.transfer(
                new vn.viettel.okrkpi.workflow.application.approval.ApproverTransferService.Command(
                    tenant, first.getId(), substitute, admin, "Lặp lại")))
        .isFalse();

    // Điều động sau khởi tạo: Ban A chuyển sang chi bộ khác; cấp 2 vẫn dùng đơn vị đã phân giải.
    UUID newCell = UUID.randomUUID();
    h.org.put(tenant, new OrgUnit(newCell, "CHI_BO", tree.dangUy(), null));
    h.org.put(tenant, new OrgUnit(tree.banA(), "PHONG_BAN", newCell, tree.coQuan()));
    h.complete(tenant, reassigned, substitute, Decision.DUYET, null);
    assertThat(h.candidateGroups(h.openTask(pid))).containsExactly(group("leader", tree.chiBo()));
    assertThat(
            h.engine
                .getHistoryService()
                .createHistoricTaskInstanceQuery()
                .taskId(first.getId())
                .singleResult()
                .getAssignee())
        .isEqualTo(substitute.toString());
  }

  @Test
  void khongChoUyQuyenThiTuChoiChuyenNguoiDuyet() {
    threeLevels(false);
    String pid = start(UUID.randomUUID());
    Task first = h.openTask(pid);
    assertThatThrownBy(
            () ->
                h.transfers.transfer(
                    new vn.viettel.okrkpi.workflow.application.approval.ApproverTransferService
                        .Command(
                        tenant, first.getId(), UUID.randomUUID(), UUID.randomUUID(), "Điều động")))
        .isInstanceOf(ApprovalSetupException.class);
    assertThat(h.openTask(pid).getAssignee()).isNull();
  }

  @Test
  void macDinhChonCapTheoDmnVaHanTheoNgayLamViec() {
    // Thứ Năm 22/10/2026 là ngày nghỉ của tenant: hạn lùi thêm một ngày làm việc.
    h.calendar.days.put(LocalDate.of(2026, 10, 22), false);
    String pid = start(UUID.randomUUID());
    Task task = h.openTask(pid);
    assertThat(h.candidateGroups(task)).containsExactly(group("leader", defaultUnit(tree)));
    LocalDate expected = LocalDate.of(2026, 10, 22);
    int days = defaultWorkingDays();
    while (days > 0) {
      expected = expected.plusDays(1);
      if (expected.getDayOfWeek().getValue() < 6) {
        days--;
      }
    }
    assertThat(task.getDueDate().toInstant())
        .isEqualTo(
            expected.atTime(17, 0).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant());
    h.complete(tenant, task, UUID.randomUUID(), Decision.DUYET, null);
    assertThat(externalJobs(pid))
        .singleElement()
        .satisfies(j -> assertThat(j.getElementId()).isEqualTo("applyApprove"));
    Map<String, Object> vars = h.engine.getRuntimeService().getVariables(pid);
    assertThat(vars).doesNotContainKey(ApprovalVariables.DMN_RESULT);
  }

  @Test
  void khoiTaoIdempotentTheoKhoaNghiepVuVaPhienBan() {
    UUID businessId = UUID.randomUUID();
    StartApprovalService.Result first = h.starter.start(command(businessId, 3));
    StartApprovalService.Result again = h.starter.start(command(businessId, 3));
    assertThat(again.created()).isFalse();
    assertThat(again.processInstanceId()).isEqualTo(first.processInstanceId());
    StartApprovalService.Result resubmitted = h.starter.start(command(businessId, 4));
    assertThat(resubmitted.created()).isTrue();
    assertThat(resubmitted.processInstanceId()).isNotEqualTo(first.processInstanceId());
    assertThat(
            h.engine
                .getRuntimeService()
                .createProcessInstanceQuery()
                .processInstanceBusinessKey(template().businessType() + ":" + businessId)
                .count())
        .isEqualTo(2);
  }

  @Test
  void bienTienTrinhDuoi4KbVaDinhNghiaKhongGanTenant() {
    threeLevels(true);
    String pid = start(UUID.randomUUID());
    Map<String, Object> vars = h.engine.getRuntimeService().getVariables(pid);
    assertThat(ApprovalVariables.sizeOf(vars)).isLessThan(ApprovalVariables.MAX_TOTAL_BYTES);
    assertThat(vars).containsKeys(ApprovalVariables.APPROVAL_LEVELS, ApprovalVariables.TENANT_ID);
    assertThat(
            h.engine
                .getRepositoryService()
                .createProcessDefinitionQuery()
                .processDefinitionKey(template().code())
                .singleResult()
                .getTenantId())
        .isEmpty();
  }

  @Test
  void loiTamThoiCuaLoiThiThuLaiSau() {
    String pid = start(UUID.randomUUID());
    h.complete(tenant, h.openTask(pid), UUID.randomUUID(), Decision.DUYET, null);
    h.core.next = CoreApprovalGateway.Outcome.RETRY;
    assertThat(h.callbacks.runOnce(WORKER)).isZero();
    ExternalWorkerJob job = externalJobs(pid).get(0);
    assertThat(job.getExceptionMessage()).isNotBlank();
    assertThat(h.ended(pid)).isFalse();
  }
}
