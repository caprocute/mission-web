package vn.viettel.okrkpi.workflow.application.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.application.WorkflowNotFoundException;
import vn.viettel.okrkpi.workflow.application.port.ApprovalProcesses;
import vn.viettel.okrkpi.workflow.application.port.CoreApprovalGateway;
import vn.viettel.okrkpi.workflow.application.port.ExternalJobQueue;
import vn.viettel.okrkpi.workflow.application.port.OrgDirectory;
import vn.viettel.okrkpi.workflow.application.port.TaskReassignment;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.Decision;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalLevel;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalParams;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalRule;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalScope;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;
import vn.viettel.okrkpi.workflow.domain.approval.OrgUnit;

/** Kiểm thử đơn vị tầng application của luồng duyệt phần 1 với cổng ra giả. */
class ApprovalApplicationTest {

  static final Instant NOW = Instant.parse("2026-10-21T02:00:00Z");
  static final UUID TENANT = UUID.randomUUID();
  static final UUID DANG_UY = UUID.randomUUID();
  static final UUID CHI_BO = UUID.randomUUID();
  static final UUID CO_QUAN = UUID.randomUUID();
  static final UUID BAN = UUID.randomUUID();

  final Map<UUID, OrgUnit> units = new HashMap<>();
  final OrgDirectory org = (t, id, on) -> Optional.ofNullable(units.get(id));
  final ApprovalLevelResolver resolver =
      new ApprovalLevelResolver(org, (t, from, to) -> Map.of(), Clock.fixed(NOW, ZoneOffset.UTC));

  ApprovalApplicationTest() {
    units.put(DANG_UY, new OrgUnit(DANG_UY, "TO_CHUC_DANG", null, null));
    units.put(CHI_BO, new OrgUnit(CHI_BO, "CHI_BO", DANG_UY, null));
    units.put(CO_QUAN, new OrgUnit(CO_QUAN, "CO_QUAN", null, null));
    units.put(BAN, new OrgUnit(BAN, "PHONG_BAN", CHI_BO, CO_QUAN));
  }

  static ApprovalRule rule(int order, ApprovalScope scope, int days) {
    return new ApprovalRule(order, "leader", scope, days);
  }

  // ------------------------------------------------------------ phân giải cấp

  @Test
  void phanGiaiBonPhamViVaCongDonHan() {
    List<ApprovalLevel> levels =
        resolver.resolve(
            new ApprovalLevelResolver.Subject(TENANT, ApprovalFlow.DANG, BAN, false),
            List.of(
                rule(4, ApprovalScope.QUAN_LY_TRUC_TIEP, 1),
                rule(1, ApprovalScope.DON_VI_TRUC_TIEP, 1),
                rule(3, ApprovalScope.CAP_UY_CO_THAM_QUYEN, 1),
                rule(2, ApprovalScope.CAP_TREN_TRUC_TIEP, 2)));
    assertThat(levels)
        .extracting(ApprovalLevel::orgUnitId)
        .containsExactly(BAN, CHI_BO, DANG_UY, BAN);
    // 21/10 (thứ Tư) + 1 = 22/10; +3 = 26/10; +4 = 27/10; +5 = 28/10; 17 giờ giờ Việt Nam.
    assertThat(levels)
        .extracting(ApprovalLevel::dueAt)
        .containsExactly(
            Instant.parse("2026-10-22T10:00:00Z"),
            Instant.parse("2026-10-26T10:00:00Z"),
            Instant.parse("2026-10-27T10:00:00Z"),
            Instant.parse("2026-10-28T10:00:00Z"));
  }

  @Test
  void nguoiDungDauVaLuongNhaNuocLenCapTrenChuyenMon() {
    List<ApprovalLevel> head =
        resolver.resolve(
            new ApprovalLevelResolver.Subject(TENANT, ApprovalFlow.NHA_NUOC, BAN, true),
            List.of(
                rule(1, ApprovalScope.QUAN_LY_TRUC_TIEP, 1),
                rule(2, ApprovalScope.CAP_TREN_TRUC_TIEP, 1)));
    assertThat(head).extracting(ApprovalLevel::orgUnitId).containsExactly(CO_QUAN, CO_QUAN);
  }

  @Test
  void loiPhanGiaiCapDuyet() {
    ApprovalLevelResolver.Subject subject =
        new ApprovalLevelResolver.Subject(TENANT, ApprovalFlow.DANG, BAN, false);
    assertThatThrownBy(() -> resolver.resolve(subject, List.of()))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(() -> resolver.resolve(subject, null))
        .isInstanceOf(ApprovalSetupException.class);
    List<ApprovalRule> nine = new ArrayList<>();
    for (int i = 1; i <= 9; i++) {
      nine.add(rule(i, ApprovalScope.DON_VI_TRUC_TIEP, 1));
    }
    assertThatThrownBy(() -> resolver.resolve(subject, nine))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () ->
                resolver.resolve(
                    subject,
                    List.of(
                        rule(1, ApprovalScope.DON_VI_TRUC_TIEP, 1),
                        rule(1, ApprovalScope.CAP_TREN_TRUC_TIEP, 1))))
        .isInstanceOf(ApprovalSetupException.class);
    ApprovalLevelResolver.Subject unknown =
        new ApprovalLevelResolver.Subject(TENANT, ApprovalFlow.DANG, UUID.randomUUID(), false);
    assertThatThrownBy(
            () -> resolver.resolve(unknown, List.of(rule(1, ApprovalScope.DON_VI_TRUC_TIEP, 1))))
        .isInstanceOf(ApprovalSetupException.class);
    ApprovalLevelResolver.Subject top =
        new ApprovalLevelResolver.Subject(TENANT, ApprovalFlow.DANG, DANG_UY, true);
    assertThatThrownBy(
            () -> resolver.resolve(top, List.of(rule(1, ApprovalScope.CAP_TREN_TRUC_TIEP, 1))))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () -> resolver.resolve(top, List.of(rule(1, ApprovalScope.CAP_UY_CO_THAM_QUYEN, 1))))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () -> resolver.resolve(top, List.of(rule(1, ApprovalScope.QUAN_LY_TRUC_TIEP, 1))))
        .isInstanceOf(ApprovalSetupException.class);
  }

  @Test
  void dungBienTuDmnHoacCapTuyChinh() {
    ApprovalLevelsAssembler assembler = new ApprovalLevelsAssembler(resolver);
    List<Map<String, Object>> rows =
        List.of(
            Map.of(
                "thuTu",
                2.0,
                "role",
                "decider",
                "scope",
                "CAP_UY_CO_THAM_QUYEN",
                "hanNgayLamViec",
                5.0),
            Map.of(
                "thuTu",
                "1",
                "role",
                "appraiser",
                "scope",
                "DON_VI_TRUC_TIEP",
                "hanNgayLamViec",
                3));
    List<ApprovalLevel> fromDmn =
        assembler.assemble(
            new ApprovalLevelsAssembler.Inputs(
                TENANT.toString(), "DANG", BAN.toString(), false, null, rows));
    assertThat(fromDmn).extracting(ApprovalLevel::role).containsExactly("appraiser", "decider");
    List<ApprovalLevel> custom =
        assembler.assemble(
            new ApprovalLevelsAssembler.Inputs(
                TENANT.toString(),
                "DANG",
                BAN.toString(),
                false,
                "manager:DON_VI_TRUC_TIEP:2",
                rows));
    assertThat(custom).extracting(ApprovalLevel::role).containsExactly("manager");
    assertThat(
            assembler.assemble(
                new ApprovalLevelsAssembler.Inputs(
                    TENANT.toString(), "DANG", BAN.toString(), false, " ", rows)))
        .hasSize(2);

    assertThatThrownBy(
            () ->
                assembler.assemble(
                    new ApprovalLevelsAssembler.Inputs(
                        TENANT.toString(), "QUAN_DOI", BAN.toString(), false, null, rows)))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () ->
                assembler.assemble(
                    new ApprovalLevelsAssembler.Inputs(
                        null, "DANG", BAN.toString(), false, null, rows)))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () ->
                assembler.assemble(
                    new ApprovalLevelsAssembler.Inputs(
                        TENANT.toString(), "DANG", "khong-phai-uuid", false, null, rows)))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () ->
                assembler.assemble(
                    new ApprovalLevelsAssembler.Inputs(
                        TENANT.toString(), "DANG", BAN.toString(), false, null, null)))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () ->
                assembler.assemble(
                    new ApprovalLevelsAssembler.Inputs(
                        TENANT.toString(), "DANG", BAN.toString(), false, "sai", rows)))
        .isInstanceOf(ApprovalSetupException.class);
    List<Map<String, Object>> badScope =
        List.of(Map.of("thuTu", 1, "role", "leader", "scope", "KHAC", "hanNgayLamViec", 1));
    assertThatThrownBy(
            () ->
                assembler.assemble(
                    new ApprovalLevelsAssembler.Inputs(
                        TENANT.toString(), "DANG", BAN.toString(), false, null, badScope)))
        .isInstanceOf(ApprovalSetupException.class);
    List<Map<String, Object>> badNumber =
        List.of(
            Map.of(
                "thuTu",
                "mot",
                "role",
                "leader",
                "scope",
                "DON_VI_TRUC_TIEP",
                "hanNgayLamViec",
                1));
    assertThatThrownBy(
            () ->
                assembler.assemble(
                    new ApprovalLevelsAssembler.Inputs(
                        TENANT.toString(), "DANG", BAN.toString(), false, null, badNumber)))
        .isInstanceOf(ApprovalSetupException.class);
    List<Map<String, Object>> missing =
        List.of(Map.of("role", "leader", "scope", "DON_VI_TRUC_TIEP"));
    assertThatThrownBy(
            () ->
                assembler.assemble(
                    new ApprovalLevelsAssembler.Inputs(
                        TENANT.toString(), "DANG", BAN.toString(), false, null, missing)))
        .isInstanceOf(ApprovalSetupException.class);
  }

  // ------------------------------------------------------------ khởi tạo

  static final class Processes implements ApprovalProcesses {
    final Map<String, String> started = new HashMap<>();
    final List<Map<String, Object>> variables = new ArrayList<>();
    int locks;

    @Override
    public void lockStart(UUID tenantId, String templateCode, BusinessKey businessKey) {
      locks++;
    }

    @Override
    public Optional<String> findStarted(
        UUID tenantId, String templateCode, BusinessKey businessKey, long objectVersion) {
      return Optional.ofNullable(started.get(templateCode + businessKey + objectVersion));
    }

    @Override
    public String start(
        UUID tenantId,
        String templateCode,
        BusinessKey key,
        UUID initiatorId,
        Map<String, Object> vars) {
      String id = UUID.randomUUID().toString();
      started.put(templateCode + key + vars.get(ApprovalVariables.PHIEN_BAN_DOI_TUONG), id);
      variables.add(vars);
      return id;
    }

    final List<String> cancelled = new ArrayList<>();

    @Override
    public int cancelWaiting(
        UUID tenantId, String templateCode, BusinessKey businessKey, String reason) {
      cancelled.add(templateCode + "|" + businessKey.value() + "|" + reason);
      return 1;
    }
  }

  static StartApprovalCommand command(
      ApprovalTemplate template,
      long version,
      String title,
      Integer plan,
      UUID task,
      String cause) {
    return new StartApprovalCommand(
        TENANT,
        template,
        UUID.fromString("00000000-0000-7000-8000-000000000001"),
        version,
        ApprovalFlow.DANG,
        BAN,
        false,
        UUID.randomUUID(),
        UUID.randomUUID(),
        title,
        "goi@1.0",
        plan,
        task,
        cause);
  }

  @Test
  void khoiTaoIdempotentVaThamSoTuBinding() {
    Processes processes = new Processes();
    Map<UUID, ApprovalParams> bindings = new HashMap<>();
    StartApprovalService service =
        new StartApprovalService(
            processes, (t, kind, flow) -> Optional.ofNullable(bindings.get(t)));
    StartApprovalService.Result first =
        service.start(command(ApprovalTemplate.DUYET_OKR, 1, "Phê duyệt", null, null, null));
    StartApprovalService.Result again =
        service.start(command(ApprovalTemplate.DUYET_OKR, 1, "Phê duyệt", null, null, null));
    assertThat(first.created()).isTrue();
    assertThat(again.created()).isFalse();
    assertThat(again.processInstanceId()).isEqualTo(first.processInstanceId());
    assertThat(processes.locks).isEqualTo(2);
    assertThat(processes.variables.get(0))
        .containsEntry(ApprovalVariables.REMIND_AFTER, "P2D")
        .containsEntry(ApprovalVariables.LOAI_HO_SO, "OKR")
        .containsKey(ApprovalVariables.PERIOD_ID)
        .doesNotContainKey(ApprovalVariables.CAP_TUY_CHINH);

    bindings.put(
        TENANT,
        new ApprovalParams(
            "P1D",
            false,
            List.of(rule(1, ApprovalScope.DON_VI_TRUC_TIEP, 2)),
            "CHUNG_THU_TO_CHUC"));
    service.start(command(ApprovalTemplate.DUYET_KE_HOACH_QUY, 2, "Kế hoạch", 4, null, null));
    assertThat(processes.variables.get(1))
        .containsEntry(ApprovalVariables.CAP_TUY_CHINH, "leader:DON_VI_TRUC_TIEP:2")
        .containsEntry(ApprovalVariables.KE_HOACH_PHIEN_BAN, 4)
        .containsEntry(ApprovalVariables.PHUONG_THUC_KY, "CHUNG_THU_TO_CHUC")
        .containsEntry(ApprovalVariables.CHO_PHEP_UY_QUYEN, false);
    UUID task = UUID.randomUUID();
    service.start(command(ApprovalTemplate.DUYET_DE_NGHI, 3, "Đề nghị", null, task, "CHU_QUAN"));
    assertThat(processes.variables.get(2))
        .containsEntry(ApprovalVariables.NHIEM_VU_ID, task.toString())
        .containsEntry(ApprovalVariables.NGUYEN_NHAN, "CHU_QUAN");
  }

  @Test
  void ketThucTienTrinhConChoNguoiDuyet() {
    Processes processes = new Processes();
    StartApprovalService service =
        new StartApprovalService(processes, (t, kind, flow) -> Optional.empty());
    UUID plan = UUID.fromString("00000000-0000-7000-8000-000000000002");
    assertThat(service.cancel(TENANT, ApprovalTemplate.DUYET_KE_HOACH_QUY, plan, " Trả lại "))
        .isEqualTo(1);
    assertThat(service.cancel(TENANT, ApprovalTemplate.DUYET_KE_HOACH_QUY, plan, null))
        .isEqualTo(1);
    service.cancel(TENANT, ApprovalTemplate.DUYET_KE_HOACH_QUY, plan, "x".repeat(300));
    assertThat(processes.cancelled.get(0))
        .isEqualTo("duyet-ke-hoach-quy|PLAN:" + plan + "|Trả lại");
    assertThat(processes.cancelled.get(1)).endsWith("|Lõi đã quyết định");
    assertThat(processes.cancelled.get(2)).endsWith("|" + "x".repeat(255));
    assertThat(processes.locks).isEqualTo(3);
  }

  @Test
  void tuChoiYeuCauKhoiTaoSai() {
    StartApprovalService service =
        new StartApprovalService(new Processes(), (t, kind, flow) -> Optional.empty());
    assertThatThrownBy(
            () -> service.start(command(ApprovalTemplate.DUYET_OKR, -1, "A", null, null, null)))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () -> service.start(command(ApprovalTemplate.DUYET_OKR, 1, " ", null, null, null)))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () ->
                service.start(
                    command(ApprovalTemplate.DUYET_OKR, 1, "x".repeat(256), null, null, null)))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () ->
                service.start(
                    command(ApprovalTemplate.DUYET_KE_HOACH_QUY, 1, "A", null, null, null)))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () ->
                service.start(command(ApprovalTemplate.DUYET_KE_HOACH_QUY, 1, "A", 0, null, null)))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () ->
                service.start(
                    command(ApprovalTemplate.DUYET_DE_NGHI, 1, "A", null, null, "KHACH_QUAN")))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () ->
                service.start(
                    command(
                        ApprovalTemplate.DUYET_DE_NGHI, 1, "A", null, UUID.randomUUID(), "KHAC")))
        .isInstanceOf(ApprovalSetupException.class);
    StartApprovalCommand longPack =
        new StartApprovalCommand(
            TENANT,
            ApprovalTemplate.DUYET_OKR,
            UUID.randomUUID(),
            1,
            ApprovalFlow.DANG,
            BAN,
            true,
            null,
            null,
            "A",
            "p".repeat(65),
            null,
            null,
            null);
    assertThatThrownBy(() -> service.start(longPack)).isInstanceOf(ApprovalSetupException.class);
    // Tiêu đề dài 255 ký tự, 3 byte mỗi ký tự, cộng tham số: vượt ngân sách biến khởi tạo.
    StartApprovalService overBudget =
        new StartApprovalService(
            new Processes(),
            (t, kind, flow) ->
                Optional.of(
                    new ApprovalParams(
                        "P1D",
                        true,
                        java.util.Collections.nCopies(
                            8,
                            new ApprovalRule(
                                1, "a".repeat(32), ApprovalScope.CAP_UY_CO_THAM_QUYEN, 30)),
                        "MYSIGN")));
    StartApprovalCommand huge =
        new StartApprovalCommand(
            TENANT,
            ApprovalTemplate.DUYET_OKR,
            UUID.randomUUID(),
            1,
            ApprovalFlow.DANG,
            BAN,
            true,
            null,
            UUID.randomUUID(),
            "ữ".repeat(255),
            "ữ".repeat(64),
            null,
            null,
            null);
    assertThatThrownBy(() -> overBudget.start(huge))
        .isInstanceOf(ApprovalSetupException.class)
        .hasMessageContaining("ngân sách");
  }

  // ------------------------------------------------------------ chuyển người duyệt

  static final class Reassign implements TaskReassignment {
    TaskReassignment.ReassignableTask task;
    final List<UUID> calls = new ArrayList<>();

    @Override
    public Optional<ReassignableTask> find(UUID tenantId, String taskId) {
      return Optional.ofNullable(task);
    }

    @Override
    public void reassign(ReassignableTask t, UUID newApproverId, UUID actorId, String reason) {
      calls.add(newApproverId);
    }
  }

  @Test
  void chuyenNguoiDuyetKiemLyDoQuyenVaTrungNguoi() {
    Reassign engine = new Reassign();
    ApproverTransferService service = new ApproverTransferService(engine);
    UUID current = UUID.randomUUID();
    UUID next = UUID.randomUUID();
    ApproverTransferService.Command ok =
        new ApproverTransferService.Command(TENANT, "t1", next, UUID.randomUUID(), " Điều động ");
    assertThatThrownBy(() -> service.transfer(ok)).isInstanceOf(WorkflowNotFoundException.class);
    engine.task = new TaskReassignment.ReassignableTask("t1", TENANT, "p1", current, false);
    assertThatThrownBy(() -> service.transfer(ok)).isInstanceOf(ApprovalSetupException.class);
    engine.task = new TaskReassignment.ReassignableTask("t1", TENANT, "p1", current, true);
    assertThat(service.transfer(ok)).isTrue();
    assertThat(engine.calls).containsExactly(next);
    engine.task = new TaskReassignment.ReassignableTask("t1", TENANT, "p1", next, true);
    assertThat(service.transfer(ok)).isFalse();
    assertThatThrownBy(
            () ->
                service.transfer(
                    new ApproverTransferService.Command(TENANT, "t1", next, next, null)))
        .isInstanceOf(ApprovalSetupException.class);
    assertThatThrownBy(
            () ->
                service.transfer(
                    new ApproverTransferService.Command(
                        TENANT, "t1", next, next, "x".repeat(2001))))
        .isInstanceOf(ApprovalSetupException.class);
  }

  // ------------------------------------------------------------ gọi lại lõi

  static final class Queue implements ExternalJobQueue {
    final List<ExternalJobQueue.CallbackJob> jobs = new ArrayList<>();
    final List<String> completed = new ArrayList<>();
    final Map<String, Integer> failed = new HashMap<>();

    @Override
    public List<CallbackJob> acquire(Set<String> topics, int max, Duration lock, String workerId) {
      return jobs;
    }

    @Override
    public void complete(CallbackJob job, String workerId) {
      completed.add(job.jobId());
    }

    @Override
    public void complete(CallbackJob job, String workerId, Map<String, Object> variables) {
      complete(job, workerId);
    }

    @Override
    public void fail(
        CallbackJob job, String workerId, String message, int retriesLeft, Duration retryAfter) {
      failed.put(job.jobId(), retriesLeft);
    }
  }

  static ExternalJobQueue.CallbackJob job(
      String id, String topic, String type, Map<String, Object> vars) {
    return new ExternalJobQueue.CallbackJob(
        id,
        topic,
        TENANT,
        "pi-" + id,
        "applyApprove",
        new BusinessKey(type, UUID.randomUUID()),
        vars,
        UUID.randomUUID(),
        "Ý kiến",
        3);
  }

  @Test
  void goiLaiLoiTheoKetQua() {
    Queue queue = new Queue();
    List<CoreApprovalGateway.Request> calls = new ArrayList<>();
    CoreApprovalGateway.Outcome[] outcome = {CoreApprovalGateway.Outcome.APPLIED};
    CoreCallbackService service =
        new CoreCallbackService(
            queue,
            r -> {
              calls.add(r);
              return outcome[0];
            });
    Map<String, Object> plan =
        Map.of(
            ApprovalVariables.PHIEN_BAN_DOI_TUONG,
            5L,
            ApprovalVariables.KE_HOACH_PHIEN_BAN,
            2,
            ApprovalVariables.PHUONG_THUC_KY,
            "MYSIGN");
    UUID taskId = UUID.randomUUID();
    Map<String, Object> request =
        Map.of(
            ApprovalVariables.PHIEN_BAN_DOI_TUONG,
            1,
            ApprovalVariables.NHIEM_VU_ID,
            taskId.toString(),
            ApprovalVariables.NGUYEN_NHAN,
            "KHACH_QUAN");
    queue.jobs.add(job("a", "core-plan-approve", "PLAN", plan));
    queue.jobs.add(job("b", "core-change-request-reject", "TASK_CHANGE_REQUEST", request));
    queue.jobs.add(
        job(
            "c",
            "core-okr-return",
            "OKR_OBJECTIVE",
            Map.of(ApprovalVariables.PHIEN_BAN_DOI_TUONG, 2L)));
    queue.jobs.add(job("d", "chu-de-la", "OKR_OBJECTIVE", Map.of()));
    queue.jobs.add(
        job("e", "core-okr-approve", "PLAN", Map.of(ApprovalVariables.PHIEN_BAN_DOI_TUONG, 2L)));
    queue.jobs.add(job("f", "core-okr-approve", "OKR_OBJECTIVE", Map.of()));
    assertThat(service.runOnce("w")).isEqualTo(3);
    assertThat(queue.completed).containsExactly("a", "b", "c");
    assertThat(queue.failed).containsEntry("d", 0).containsEntry("e", 0).containsEntry("f", 0);
    assertThat(calls.get(0).planVersionNo()).isEqualTo(2);
    assertThat(calls.get(0).objectVersion()).isEqualTo(5);
    assertThat(calls.get(1).decision()).isEqualTo(Decision.TRA_LAI);
    assertThat(calls.get(1).taskId()).isEqualTo(taskId);
    assertThat(calls.get(1).cause()).isEqualTo("KHACH_QUAN");
    assertThat(calls.get(2).idempotencyKey())
        .isEqualTo(CoreCallbackService.idempotencyKey(queue.jobs.get(2)))
        .isEqualTo(CoreCallbackService.idempotencyKey(job("c", "x", "OKR_OBJECTIVE", Map.of())));

    queue.jobs.clear();
    queue.completed.clear();
    queue.failed.clear();
    queue.jobs.add(
        job(
            "g",
            "core-okr-approve",
            "OKR_OBJECTIVE",
            Map.of(ApprovalVariables.PHIEN_BAN_DOI_TUONG, 1L)));
    outcome[0] = CoreApprovalGateway.Outcome.RETRY;
    assertThat(service.runOnce("w")).isZero();
    assertThat(queue.failed).containsEntry("g", 2);
    outcome[0] = CoreApprovalGateway.Outcome.REJECTED;
    service.runOnce("w");
    assertThat(queue.failed).containsEntry("g", 0);
    assertThat(CoreCallbackService.topics()).hasSize(6).doesNotContain("notify-remind");
  }

  @Test
  void ngayKhoiTaoTheoGioVietNam() {
    // 23:30 UTC ngày 20/10 là 06:30 ngày 21/10 giờ Việt Nam: hạn tính từ ngày 21/10.
    ApprovalLevelResolver late =
        new ApprovalLevelResolver(
            org,
            (t, from, to) -> {
              assertThat(from).isEqualTo(LocalDate.of(2026, 10, 21));
              return Map.of();
            },
            Clock.fixed(Instant.parse("2026-10-20T23:30:00Z"), ZoneOffset.UTC));
    assertThat(
            late.resolve(
                    new ApprovalLevelResolver.Subject(TENANT, ApprovalFlow.DANG, BAN, false),
                    List.of(rule(1, ApprovalScope.DON_VI_TRUC_TIEP, 1)))
                .get(0)
                .dueAt())
        .isEqualTo(Instant.parse("2026-10-22T10:00:00Z"));
  }

  @Test
  void viecGoiLaiKhongBienThiDungMapRong() {
    assertThat(job("j-0", "core-approve", "OKR", null).variables()).isEmpty();
    assertThat(job("j-1", "core-approve", "OKR", Map.of("a", 1)).variables()).containsEntry("a", 1);
  }
}
