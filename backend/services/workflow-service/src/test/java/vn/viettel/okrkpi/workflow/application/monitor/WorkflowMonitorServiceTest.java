package vn.viettel.okrkpi.workflow.application.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.workflow.application.WorkflowNotFoundException;
import vn.viettel.okrkpi.workflow.application.port.AssigneeDirectory;
import vn.viettel.okrkpi.workflow.application.port.AssigneeSeparation;
import vn.viettel.okrkpi.workflow.application.port.CurrentApprover;
import vn.viettel.okrkpi.workflow.application.port.ExtensionLimits;
import vn.viettel.okrkpi.workflow.application.port.InstanceMonitorStore;
import vn.viettel.okrkpi.workflow.application.port.MonitorAccessPolicy;
import vn.viettel.okrkpi.workflow.application.port.MonitorEngine;
import vn.viettel.okrkpi.workflow.application.port.MonitorLedger;
import vn.viettel.okrkpi.workflow.application.port.TenantScope;
import vn.viettel.okrkpi.workflow.application.port.WorkingCalendar;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.CandidateGroup;
import vn.viettel.okrkpi.workflow.domain.InboxPosition;
import vn.viettel.okrkpi.workflow.domain.ProcessRef;
import vn.viettel.okrkpi.workflow.domain.TaskProjection;
import vn.viettel.okrkpi.workflow.domain.monitor.InstanceFilter;
import vn.viettel.okrkpi.workflow.domain.monitor.MonitoredTask;
import vn.viettel.okrkpi.workflow.domain.monitor.WorkflowDiagram;

/**
 * Tầng application của giám sát tiến trình SCR-1506 với bản giả các cổng ra: phạm vi tenant và
 * cell, che 404 khi OPA từ chối đối tượng tenant khác, lý do bắt buộc, If-Match, người nhận hợp lệ,
 * tách vai trò, giới hạn gia hạn, ghi engine, bảng chiếu và sổ cái đúng một lần.
 */
class WorkflowMonitorServiceTest {

  static final Instant NOW = Instant.parse("2026-12-07T02:00:00Z");
  static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

  final UUID tenant = UUID.randomUUID();
  final UUID otherTenant = UUID.randomUUID();
  final UUID admin = UUID.randomUUID();
  final UUID unit = UUID.randomUUID();
  final UUID newcomer = UUID.randomUUID();
  final UUID initiator = UUID.randomUUID();
  final BusinessKey key = new BusinessKey("APPRAISAL_PROFILE", UUID.randomUUID());

  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final Store store = new Store();
  final Engine engine = new Engine();
  final Policy policy = new Policy();
  final Directory directory = new Directory();
  final Separation separation = new Separation();
  final Limits limits = new Limits();
  final Calendar calendar = new Calendar();
  final Ledger ledger = new Ledger();
  final Scope scope = new Scope();
  Approver caller;

  WorkflowMonitorService service;

  @BeforeEach
  void setUp() {
    caller = new Approver(tenant, admin, Set.of("tenant_admin"), null);
    CurrentApprover current = () -> caller;
    CursorCodec cursors = new CursorCodec(new byte[32], Duration.ofHours(1), clock::instant);
    service =
        new WorkflowMonitorService(
            store,
            engine,
            policy,
            directory,
            separation,
            limits,
            calendar,
            ledger,
            scope,
            current,
            cursors,
            new MonitorSettings("CELL-HN", 5, VN),
            clock);
  }

  TaskProjection row(UUID tenantId, String taskId, Instant due) {
    return new TaskProjection(
        taskId,
        tenantId,
        null,
        "appraiser",
        unit,
        key,
        "duyet-ho-so-danh-gia",
        "Hồ sơ đánh giá năm 2026",
        null,
        due,
        NOW.minusSeconds(86_400),
        2,
        new ProcessRef("p-" + taskId, "review", null, 3, NOW.minusSeconds(2 * 86_400)));
  }

  MonitoredTask task(UUID tenantId, Instant due) {
    return new MonitoredTask(
        "t-1",
        tenantId,
        "p-1",
        "review",
        null,
        List.of(new CandidateGroup(tenantId, "appraiser", unit)),
        2,
        due,
        key,
        initiator,
        "duyet-ho-so-danh-gia");
  }

  void open(MonitoredTask t) {
    engine.tasks.put(t.taskId(), t);
    store.rows.put(t.taskId(), row(t.tenantId(), t.taskId(), t.dueAt()));
  }

  // ------------------------------------------------------------------ danh sách

  @Test
  void danhSachTenantChiDocTenantNguoiGoiVaPhanTrang() {
    for (int i = 0; i < 3; i++) {
      store.rows.put("t" + i, row(tenant, "t" + i, NOW.plusSeconds(3600L * (i + 1))));
    }
    store.rows.put("x", row(otherTenant, "x", NOW.plusSeconds(60)));
    WorkflowMonitorService.InstancePage first = service.list(null, null, null, null, null, 2, null);
    assertThat(first.items()).extracting(s -> s.currentTaskId()).containsExactly("t0", "t1");
    assertThat(first.nextCursor()).isNotNull();
    assertThat(first.items().get(0).title()).isEqualTo("Hồ sơ đánh giá năm 2026");
    assertThat(first.items().get(0).cellCode()).isEqualTo("CELL-HN");
    WorkflowMonitorService.InstancePage second =
        service.list(null, null, null, null, null, 2, first.nextCursor());
    assertThat(second.items()).extracting(s -> s.currentTaskId()).containsExactly("t2");
    assertThat(second.nextCursor()).isNull();
    assertThat(policy.calls).containsOnly("xem");
    assertThat(policy.resources.get(0).tenantId()).isEqualTo(tenant.toString());
    assertThat(store.filters.get(0).tenantId()).isEqualTo(tenant);
    assertThat(store.filters.get(0).unitId()).isNull();
    assertThat(first.items().get(0).rowVersion()).isPositive();
  }

  @Test
  void danhSachLocTheoDonViXuLyChuyenXuongBoLoc() {
    UUID unit = UUID.randomUUID();
    service.list(null, null, null, null, unit, 20, null);
    service.list(null, null, "CELL-HN", null, unit, 20, null);
    assertThat(store.filters).extracting(InstanceFilter::unitId).containsExactly(unit, unit);
    assertThat(store.filters.get(1).cellScope()).isTrue();
  }

  @Test
  void conTroCuaBoLocKhacBiTuChoi() {
    for (int i = 0; i < 3; i++) {
      store.rows.put("t" + i, row(tenant, "t" + i, null));
    }
    String next = service.list(null, null, null, null, null, 1, null).nextCursor();
    assertThatThrownBy(() -> service.list("duyet-okr", null, null, null, null, 1, next))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void phamViCellKiemOpaTheoTaiNguyenCellVaCheNoiDung() {
    store.rows.put("t1", row(tenant, "t1", NOW.minusSeconds(86_400 * 2)));
    store.rows.put("t2", row(otherTenant, "t2", null));
    WorkflowMonitorService.InstancePage page =
        service.list(null, true, "cell-hn", null, null, 20, null);
    assertThat(policy.resources.get(0).tenantId()).isEqualTo("cell:cell-hn");
    assertThat(store.filters.get(0).cellScope()).isTrue();
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).title()).isNull();
    assertThat(page.items().get(0).stuck()).isTrue();
  }

  @Test
  void cellKhacTraTrangRongVaVanKiemQuyen() {
    store.rows.put("t1", row(tenant, "t1", null));
    WorkflowMonitorService.InstancePage page =
        service.list(null, null, "CELL-HCM", null, null, 20, null);
    assertThat(page.items()).isEmpty();
    assertThat(page.nextCursor()).isNull();
    assertThat(policy.calls).containsExactly("xem");
    assertThat(store.filters).isEmpty();
  }

  @Test
  void opaTuChoiDanhSachThiKhongDocBangChieu() {
    policy.deny = "COMMON-403-OUT-OF-SCOPE";
    assertThatThrownBy(() -> service.list(null, null, "CELL-HN", null, null, 20, null))
        .isInstanceOf(BusinessException.class);
    assertThat(store.filters).isEmpty();
  }

  // ------------------------------------------------------------------ sơ đồ

  @Test
  void soDoTenantMinh() {
    engine.diagrams.put(tenant, diagram(tenant));
    assertThat(service.diagram(key.value()).bpmnXml()).isEqualTo("<definitions/>");
    assertThat(policy.calls).containsExactly("so-do");
    assertThat(policy.resources.get(0).type()).isEqualTo("appraisal_profile");
  }

  @Test
  void soDoTenantKhacChoPhamViNenTangCon404KhiOpaTuChoi() {
    engine.diagrams.put(otherTenant, diagram(otherTenant));
    assertThat(service.diagram(key.value()).tenantId()).isEqualTo(otherTenant);
    policy.deniedTenants.add(otherTenant.toString());
    assertThatThrownBy(() -> service.diagram(key.value()))
        .isInstanceOf(WorkflowNotFoundException.class);
  }

  @Test
  void soDoKhoaSaiHoacKhongCo() {
    assertThatThrownBy(() -> service.diagram("sai")).isInstanceOf(WorkflowNotFoundException.class);
    assertThatThrownBy(() -> service.diagram(key.value()))
        .isInstanceOf(WorkflowNotFoundException.class);
  }

  @Test
  void loiKhacOutOfScopeKhongBiChe() {
    engine.diagrams.put(otherTenant, diagram(otherTenant));
    policy.deny = "WORKFLOW-403-SELF-APPROVAL";
    assertThatThrownBy(() -> service.diagram(key.value()))
        .isInstanceOf(BusinessException.class)
        .isNotInstanceOf(WorkflowNotFoundException.class);
  }

  WorkflowDiagram diagram(UUID tenantId) {
    return new WorkflowDiagram(
        tenantId, key, "duyet", 1, "<definitions/>", List.of("review"), List.of("start"));
  }

  // ------------------------------------------------------------------ chuyển người xử lý

  WorkflowMonitorService.ReassignCommand reassign(Long expected, String reason) {
    return new WorkflowMonitorService.ReassignCommand("t-1", expected, newcomer, reason);
  }

  @Test
  void chuyenNguoiXuLyGhiEngineBangChieuSoCaiTrongTenantCuaTienTrinh() {
    open(task(tenant, NOW.plusSeconds(86_400)));
    directory.active.add(newcomer);
    WorkflowMonitorService.TaskResult r = service.reassign(reassign(2L, "  Nghỉ phép  "));
    assertThat(engine.reasons).containsExactly("Nghỉ phép");
    assertThat(r.projection().assigneeId()).isEqualTo(newcomer);
    assertThat(r.projection().rowVersion()).isEqualTo(3);
    assertThat(ledger.events).containsExactly("reassigned:t-1:" + newcomer + ":3");
    assertThat(scope.entered).containsExactly(tenant);
    assertThat(directory.units).containsExactly(unit);
    assertThat(separation.checked).containsExactly(newcomer);
    assertThat(policy.calls).containsExactly("chuyen-nguoi-xu-ly");
    assertThat(policy.resources.get(0).ownerUnitId()).isEqualTo(unit.toString());
  }

  @Test
  void chuyenNguoiXuLyKhongCoNhomUngVienKiemPhanCongTrongTenant() {
    MonitoredTask t =
        new MonitoredTask(
            "t-1", tenant, "p", "review", UUID.randomUUID(), null, 2, null, key, null, "m");
    open(t);
    directory.active.add(newcomer);
    service.reassign(reassign(null, "Điều động"));
    assertThat(directory.units).containsExactly((UUID) null);
  }

  @Test
  void chuyenNguoiXuLyThieuLyDo422() {
    assertThatThrownBy(() -> service.reassign(reassign(2L, " ")))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThat(engine.reasons).isEmpty();
  }

  @Test
  void chuyenNguoiXuLyViecKhongCo404() {
    assertThatThrownBy(() -> service.reassign(reassign(2L, "Lý do")))
        .isInstanceOf(WorkflowNotFoundException.class);
  }

  @Test
  void chuyenNguoiXuLyTenantKhacBiOpaTuChoiTra404() {
    open(task(otherTenant, null));
    policy.deniedTenants.add(otherTenant.toString());
    assertThatThrownBy(() -> service.reassign(reassign(2L, "Lý do")))
        .isInstanceOf(WorkflowNotFoundException.class);
    assertThat(scope.entered).isEmpty();
  }

  @Test
  void chuyenNguoiXuLyLechPhienBan409() {
    open(task(tenant, null));
    assertThatThrownBy(() -> service.reassign(reassign(7L, "Lý do")))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void chuyenChoChinhNguoiDangXuLy422() {
    MonitoredTask t =
        new MonitoredTask("t-1", tenant, "p", "review", newcomer, null, 2, null, key, null, "m");
    open(t);
    assertThatThrownBy(() -> service.reassign(reassign(2L, "Lý do")))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
  }

  @Test
  void nguoiNhanKhongCoPhanCongODonVi422() {
    open(task(tenant, null));
    assertThatThrownBy(() -> service.reassign(reassign(2L, "Lý do")))
        .satisfies(e -> assertThat(code(e)).isEqualTo("WORKFLOW-422-ASSIGNEE-NOT-ELIGIBLE"));
    assertThat(ledger.events).isEmpty();
  }

  @Test
  void nguoiNhanDaNghiHuuHoacThoiViec422KhongGhiGi() {
    for (String status : List.of("NGHI_HUU", "THOI_VIEC", "KHONG_RO")) {
      open(task(tenant, null));
      directory.active.add(newcomer);
      directory.statuses.put(newcomer, status);
      assertThatThrownBy(() -> service.reassign(reassign(2L, "Lý do")))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> {
                assertThat(code(e)).isEqualTo("WORKFLOW-422-ASSIGNEE-NOT-ELIGIBLE");
                assertThat(e.detail()).contains("nghỉ hưu hoặc thôi việc");
              });
    }
    assertThat(separation.checked).isEmpty();
    assertThat(engine.reasons).isEmpty();
    assertThat(ledger.events).isEmpty();
  }

  @Test
  void nguoiNhanDangCongTacTamNghiHoacChuaCoBanSaoNhanSuThiDuocChuyen() {
    for (String status : new String[] {"DANG_CONG_TAC", "TAM_NGHI", null}) {
      open(task(tenant, null));
      directory.active.add(newcomer);
      if (status == null) {
        directory.statuses.remove(newcomer);
      } else {
        directory.statuses.put(newcomer, status);
      }
      service.reassign(reassign(2L, "Lý do"));
    }
    assertThat(separation.checked).containsExactly(newcomer, newcomer, newcomer);
  }

  @Test
  void nguoiNhanViPhamTachVaiTro403KhongGhiGi() {
    open(task(tenant, null));
    directory.active.add(newcomer);
    separation.reject = true;
    assertThatThrownBy(() -> service.reassign(reassign(2L, "Lý do")))
        .satisfies(e -> assertThat(code(e)).isEqualTo("WORKFLOW-403-SELF-APPROVAL"));
    assertThat(engine.reasons).isEmpty();
    assertThat(ledger.events).isEmpty();
  }

  // ------------------------------------------------------------------ gia hạn

  WorkflowMonitorService.ExtendCommand extend(Instant due) {
    return new WorkflowMonitorService.ExtendCommand("t-1", 2L, due, "Chờ bổ sung minh chứng");
  }

  @Test
  void giaHanTheoGioiHanMacDinhVaGhiSoCai() {
    Instant due = Instant.parse("2026-12-14T10:00:00Z");
    open(task(tenant, due));
    Instant requested = Instant.parse("2026-12-18T10:00:00Z");
    WorkflowMonitorService.TaskResult r = service.extend(extend(requested));
    assertThat(r.projection().dueAt()).isEqualTo(requested);
    assertThat(r.projection().rowVersion()).isEqualTo(3);
    assertThat(ledger.events).containsExactly("extended:t-1:" + requested + ":3");
    assertThat(calendar.ranges).containsExactly(LocalDate.of(2026, 12, 14));
    assertThat(policy.calls).containsExactly("gia-han-buoc");
  }

  @Test
  void giaHanVuotThamSoMauCuaTenant422() {
    Instant due = Instant.parse("2026-12-14T10:00:00Z");
    open(task(tenant, due));
    limits.days = OptionalInt.of(2);
    assertThatThrownBy(() -> service.extend(extend(Instant.parse("2026-12-18T10:00:00Z"))))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION");
              assertThat(((BusinessException) e).detail()).contains("tối đa 2 ngày làm việc");
              assertThat(((BusinessException) e).errors().get(0).field()).isEqualTo("dueAt");
            });
    assertThat(engine.dues).isEmpty();
    assertThat(ledger.events).isEmpty();
  }

  @Test
  void giaHanVeTruocHanCuHoacQuaKhu422() {
    Instant due = Instant.parse("2026-12-14T10:00:00Z");
    open(task(tenant, due));
    assertThatThrownBy(() -> service.extend(extend(due.minusSeconds(3600))))
        .satisfies(
            e ->
                assertThat(((BusinessException) e).detail())
                    .isEqualTo("Hạn mới phải sau hạn hiện tại của bước."));
    assertThatThrownBy(() -> service.extend(extend(NOW.minusSeconds(60))))
        .satisfies(
            e ->
                assertThat(((BusinessException) e).detail())
                    .isEqualTo("Hạn mới phải sau thời điểm hiện tại."));
  }

  @Test
  void giaHanBuocQuaHanTinhTuHienTai() {
    open(task(tenant, NOW.minusSeconds(86_400)));
    Instant requested = Instant.parse("2026-12-09T02:00:00Z");
    service.extend(extend(requested));
    assertThat(engine.dues).containsExactly(requested);
    assertThat(calendar.ranges).containsExactly(LocalDate.of(2026, 12, 7));
  }

  @Test
  void giaHanThieuLyDoHoacKhongCoViec() {
    assertThatThrownBy(
            () ->
                service.extend(
                    new WorkflowMonitorService.ExtendCommand("t-1", 2L, NOW.plusSeconds(60), "")))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> service.extend(extend(NOW.plusSeconds(60))))
        .isInstanceOf(WorkflowNotFoundException.class);
  }

  @Test
  void quanTriNenTangThaoTacTrongTenantCuaTienTrinh() {
    caller = new Approver(otherTenant, admin, Set.of("platform_admin"), null);
    Instant due = Instant.parse("2026-12-14T10:00:00Z");
    open(task(tenant, due));
    service.extend(extend(Instant.parse("2026-12-15T10:00:00Z")));
    assertThat(scope.entered).containsExactly(tenant);
    assertThat(policy.resources.get(0).tenantId()).isEqualTo(tenant.toString());
  }

  static String code(Throwable e) {
    return ((BusinessException) e).errorCode().code();
  }

  // ------------------------------------------------------------------ bản giả

  static final class Policy implements MonitorAccessPolicy {
    final List<String> calls = new ArrayList<>();
    final List<AuthzResource> resources = new ArrayList<>();
    final Set<String> deniedTenants = new HashSet<>();
    String deny;

    private void check(String action, AuthzResource resource) {
      calls.add(action);
      resources.add(resource);
      if (deny != null) {
        throw new BusinessException(deny);
      }
      if (deniedTenants.contains(resource.tenantId())) {
        throw new BusinessException("COMMON-403-OUT-OF-SCOPE");
      }
    }

    @Override
    public void checkView(AuthzResource resource) {
      check("xem", resource);
    }

    @Override
    public void checkDiagram(AuthzResource resource) {
      check("so-do", resource);
    }

    @Override
    public void checkReassign(AuthzResource resource) {
      check("chuyen-nguoi-xu-ly", resource);
    }

    @Override
    public void checkExtend(AuthzResource resource) {
      check("gia-han-buoc", resource);
    }
  }

  static final class Store implements InstanceMonitorStore {
    final Map<String, TaskProjection> rows = new HashMap<>();
    final List<InstanceFilter> filters = new ArrayList<>();

    @Override
    public List<TaskProjection> find(InstanceFilter filter, InboxPosition after, int fetchSize) {
      filters.add(filter);
      Comparator<TaskProjection> order =
          Comparator.comparing(
                  TaskProjection::dueAt, Comparator.nullsLast(Comparator.naturalOrder()))
              .thenComparing(TaskProjection::taskId);
      return rows.values().stream()
          .filter(p -> filter.cellScope() || p.tenantId().equals(filter.tenantId()))
          .filter(
              p ->
                  filter.stuck() == null
                      || filter.stuck()
                          == (p.overdueAt(filter.now())
                              || (p.assigneeId() == null && p.candidateRole() == null)))
          .filter(
              p -> filter.templateCode() == null || filter.templateCode().equals(p.templateCode()))
          .filter(p -> after == null || isAfter(p, after))
          .sorted(order)
          .limit(fetchSize)
          .toList();
    }

    private static boolean isAfter(TaskProjection p, InboxPosition after) {
      if (after.dueAt() == null) {
        return p.dueAt() == null && p.taskId().compareTo(after.taskId()) > 0;
      }
      if (p.dueAt() == null) {
        return true;
      }
      int c = p.dueAt().compareTo(after.dueAt());
      return c > 0 || (c == 0 && p.taskId().compareTo(after.taskId()) > 0);
    }

    @Override
    public Optional<TaskProjection> find(UUID tenantId, String taskId) {
      return Optional.ofNullable(rows.get(taskId)).filter(p -> p.tenantId().equals(tenantId));
    }

    @Override
    public void updateDue(UUID tenantId, String taskId, Instant dueAt, int rowVersion) {
      TaskProjection p = rows.get(taskId);
      rows.put(
          taskId,
          new TaskProjection(
              p.taskId(),
              p.tenantId(),
              p.assigneeId(),
              p.candidateRole(),
              p.candidateUnitId(),
              p.businessKey(),
              p.templateCode(),
              p.title(),
              p.periodId(),
              dueAt,
              p.createdAt(),
              rowVersion,
              p.process()));
    }

    @Override
    public void updateAssignee(UUID tenantId, String taskId, UUID assigneeId, int rowVersion) {
      TaskProjection p = rows.get(taskId);
      rows.put(
          taskId,
          new TaskProjection(
              p.taskId(),
              p.tenantId(),
              assigneeId,
              p.candidateRole(),
              p.candidateUnitId(),
              p.businessKey(),
              p.templateCode(),
              p.title(),
              p.periodId(),
              p.dueAt(),
              p.createdAt(),
              rowVersion,
              p.process()));
    }
  }

  static final class Engine implements MonitorEngine {
    final Map<String, MonitoredTask> tasks = new HashMap<>();
    final Map<UUID, WorkflowDiagram> diagrams = new HashMap<>();
    final List<String> reasons = new ArrayList<>();
    final List<Instant> dues = new ArrayList<>();

    @Override
    public Optional<MonitoredTask> findTask(String taskId) {
      return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public int reassign(MonitoredTask task, UUID newAssigneeId, UUID actorId, String reason) {
      reasons.add(reason);
      return task.revision() + 1;
    }

    @Override
    public int extend(MonitoredTask task, Instant newDueAt, UUID actorId, String reason) {
      dues.add(newDueAt);
      return task.revision() + 1;
    }

    @Override
    public Optional<WorkflowDiagram> diagram(BusinessKey businessKey, UUID tenantId) {
      if (tenantId != null) {
        return Optional.ofNullable(diagrams.get(tenantId));
      }
      return diagrams.values().stream().findFirst();
    }
  }

  static final class Directory implements AssigneeDirectory {
    final Set<UUID> active = new HashSet<>();
    final List<UUID> units = new ArrayList<>();
    final Map<UUID, String> statuses = new HashMap<>();

    @Override
    public boolean activeIn(UUID tenantId, UUID personId, UUID unitId, LocalDate on) {
      units.add(unitId);
      return active.contains(personId);
    }

    @Override
    public Optional<String> employmentStatus(UUID tenantId, UUID personId) {
      return Optional.ofNullable(statuses.get(personId));
    }
  }

  static final class Separation implements AssigneeSeparation {
    final List<UUID> checked = new ArrayList<>();
    boolean reject;

    @Override
    public void check(MonitoredTask task, UUID newAssigneeId) {
      checked.add(newAssigneeId);
      if (reject) {
        throw new BusinessException("WORKFLOW-403-SELF-APPROVAL");
      }
    }
  }

  static final class Limits implements ExtensionLimits {
    OptionalInt days = OptionalInt.empty();

    @Override
    public OptionalInt maxWorkingDays(UUID tenantId, String templateCode) {
      return days;
    }
  }

  static final class Calendar implements WorkingCalendar {
    final List<LocalDate> ranges = new ArrayList<>();

    @Override
    public Map<LocalDate, Boolean> overrides(UUID tenantId, LocalDate from, LocalDate to) {
      ranges.add(from);
      return Map.of();
    }
  }

  static final class Ledger implements MonitorLedger {
    final List<String> events = new ArrayList<>();

    @Override
    public void reassigned(MonitoredTask task, UUID newAssigneeId, UUID actorId, int newRevision) {
      events.add("reassigned:" + task.taskId() + ":" + newAssigneeId + ":" + newRevision);
    }

    @Override
    public void extended(MonitoredTask task, Instant newDueAt, UUID actorId, int newRevision) {
      events.add("extended:" + task.taskId() + ":" + newDueAt + ":" + newRevision);
    }
  }

  static final class Scope implements TenantScope {
    final List<UUID> entered = new ArrayList<>();

    @Override
    public <T> T within(UUID tenantId, Supplier<T> work) {
      entered.add(tenantId);
      return work.get();
    }
  }
}
