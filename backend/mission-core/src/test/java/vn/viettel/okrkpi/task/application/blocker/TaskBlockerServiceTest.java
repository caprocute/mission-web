package vn.viettel.okrkpi.task.application.blocker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.AssigneeRow;
import vn.viettel.okrkpi.task.application.blocker.port.BlockerNotices;
import vn.viettel.okrkpi.task.application.blocker.port.TaskBlockers;
import vn.viettel.okrkpi.task.application.blocker.port.TaskBlockers.BlockerRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.domain.blocker.BlockerRules;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

class TaskBlockerServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID TASK = UUID.randomUUID();
  static final UUID BLOCKER = UUID.randomUUID();
  static final UUID ASSIGNER = UUID.randomUUID();
  static final UUID LEAD = UUID.randomUUID();
  static final UUID OUTSIDER = UUID.randomUUID();
  static final UUID HEAD = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();

  TaskBlockers blockers = mock(TaskBlockers.class);
  TaskLifecycleRepository tasks = mock(TaskLifecycleRepository.class);
  TaskDraftRepository assignees = mock(TaskDraftRepository.class);
  PeriodApi periods = mock(PeriodApi.class);
  OrgDirectoryApi org = mock(OrgDirectoryApi.class);
  BlockerNotices notices = mock(BlockerNotices.class);
  TaskBlockerService service;

  @BeforeEach
  void setUp() {
    service =
        new TaskBlockerService(
            blockers,
            tasks,
            assignees,
            periods,
            org,
            notices,
            Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneOffset.UTC));
    when(assignees.assignees(TENANT, 20264, TASK))
        .thenReturn(List.of(new AssigneeRow("PERSON", LEAD, "CHU_TRI")));
  }

  static TaskRow task(TaskStatus status) {
    return new TaskRow(
        TASK,
        UUID.randomUUID(),
        20264,
        "NV-2026-0157",
        "Báo cáo quý",
        null,
        "CHUYEN_MON",
        "KE_HOACH",
        null,
        ASSIGNER,
        null,
        null,
        LocalDate.of(2026, 12, 1),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        status,
        false,
        null,
        "v1",
        3);
  }

  static BlockerRow blocker(String status, UUID handler) {
    return new BlockerRow(
        BLOCKER,
        "BN-0157-01",
        TASK,
        UUID.randomUUID(),
        20264,
        "Thiếu số liệu",
        "NGUON_LUC",
        "KHACH_QUAN",
        "CAO",
        handler,
        null,
        status,
        null,
        null,
        LEAD,
        Instant.parse("2026-10-20T03:00:00Z"),
        2);
  }

  static TaskActor actor(UUID id) {
    return new TaskActor(id, Set.of("member"));
  }

  @Test
  void reportWritesBlockerAndNotifiesAssigner() {
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN)));
    when(blockers.nextSeq(TENANT, 20264, TASK)).thenReturn(1);
    when(blockers.find(eq(TENANT), eq(20264), eq(TASK), any()))
        .thenReturn(Optional.of(blocker("MO", null)));
    service.reportBlocker(
        TENANT,
        TASK,
        new TaskBlockerService.Report("Thiếu số liệu", "NGUON_LUC", "KHACH_QUAN", "CAO", null),
        actor(LEAD));
    ArgumentCaptor<TaskBlockers.NewBlocker> nb =
        ArgumentCaptor.forClass(TaskBlockers.NewBlocker.class);
    verify(blockers).insert(eq(TENANT), nb.capture(), any());
    assertThat(nb.getValue().code()).isEqualTo("BN-0157-01");
    ArgumentCaptor<BlockerNotices.Notice> n = ArgumentCaptor.forClass(BlockerNotices.Notice.class);
    verify(notices).notice(n.capture());
    assertThat(n.getValue().recipients()).containsExactly(ASSIGNER);
    assertThat(n.getValue().template()).isEqualTo("DIEM_NGHEN_MOI");
  }

  @Test
  void reportRejectsOutsiderClosedTaskAndBadInput() {
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN)));
    var ok = new TaskBlockerService.Report("x", "NGUON_LUC", "KHACH_QUAN", "CAO", null);
    assertThatThrownBy(() -> service.reportBlocker(TENANT, TASK, ok, actor(OUTSIDER)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    var bad = new TaskBlockerService.Report(" ", "SAI", null, "CAO", null);
    assertThatThrownBy(() -> service.reportBlocker(TENANT, TASK, bad, actor(LEAD)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-422-VALIDATION"));
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.HOAN_THANH)));
    assertThatThrownBy(() -> service.reportBlocker(TENANT, TASK, ok, actor(LEAD)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("TASK-409-INVALID-STATE"));
    verify(blockers, never()).insert(any(), any(), any());
  }

  @Test
  void handleAssignsHandlerMovesToInProgressAndNotifies() {
    UUID handler = UUID.randomUUID();
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN)));
    when(blockers.find(TENANT, 20264, TASK, BLOCKER))
        .thenReturn(Optional.of(blocker("MO", null)), Optional.of(blocker("DANG_XU_LY", handler)));
    when(blockers.change(eq(TENANT), eq(20264), eq(BLOCKER), eq(2L), any(), any()))
        .thenReturn(true);
    service.handleBlocker(
        TENANT,
        TASK,
        BLOCKER,
        2,
        new TaskBlockerService.Patch(true, handler, false, null, null, null),
        actor(ASSIGNER));
    ArgumentCaptor<TaskBlockers.Change> c = ArgumentCaptor.forClass(TaskBlockers.Change.class);
    verify(blockers).change(eq(TENANT), eq(20264), eq(BLOCKER), eq(2L), c.capture(), any());
    assertThat(c.getValue().status()).isEqualTo("DANG_XU_LY");
    ArgumentCaptor<BlockerNotices.Notice> n = ArgumentCaptor.forClass(BlockerNotices.Notice.class);
    verify(notices).notice(n.capture());
    assertThat(n.getValue().recipients()).containsExactly(handler);
  }

  @Test
  void handleRejectsWrongVersionOutsiderClosedAndMissingNote() {
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN)));
    when(blockers.find(TENANT, 20264, TASK, BLOCKER)).thenReturn(Optional.of(blocker("MO", null)));
    var close = new TaskBlockerService.Patch(false, null, false, null, "DONG", null);
    assertThatThrownBy(
            () -> service.handleBlocker(TENANT, TASK, BLOCKER, 1, close, actor(ASSIGNER)))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> service.handleBlocker(TENANT, TASK, BLOCKER, 2, close, actor(LEAD)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(
            () -> service.handleBlocker(TENANT, TASK, BLOCKER, 2, close, actor(ASSIGNER)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-422-VALIDATION"));
    when(blockers.find(TENANT, 20264, TASK, BLOCKER))
        .thenReturn(Optional.of(blocker("DONG", null)));
    var reopen = new TaskBlockerService.Patch(false, null, false, null, "MO", null);
    assertThatThrownBy(
            () -> service.handleBlocker(TENANT, TASK, BLOCKER, 2, reopen, actor(ASSIGNER)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("TASK-409-INVALID-STATE"));
    verify(blockers, never()).change(any(), anyInt(), any(), anyLong(), any(), any());
  }

  @Test
  void escalateToUnitHeadsSendsOneNotice() {
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN)));
    when(blockers.find(TENANT, 20264, TASK, BLOCKER))
        .thenReturn(Optional.of(blocker("MO", null)), Optional.of(blocker("DANG_XU_LY", HEAD)));
    when(blockers.change(eq(TENANT), eq(20264), eq(BLOCKER), eq(2L), any(), any()))
        .thenReturn(true);
    when(org.headsOf(eq(TENANT), eq(UNIT), any())).thenReturn(List.of(HEAD));
    service.escalateBlocker(
        TENANT, TASK, BLOCKER, 2, null, UNIT, "Cần cấp trên hỗ trợ", actor(ASSIGNER));
    ArgumentCaptor<TaskBlockers.Change> c = ArgumentCaptor.forClass(TaskBlockers.Change.class);
    verify(blockers).change(eq(TENANT), eq(20264), eq(BLOCKER), eq(2L), c.capture(), any());
    assertThat(c.getValue().escalatedTo()).isEqualTo(HEAD);
    assertThat(c.getValue().handlerId()).isEqualTo(HEAD);
    ArgumentCaptor<BlockerNotices.Notice> n = ArgumentCaptor.forClass(BlockerNotices.Notice.class);
    verify(notices).notice(n.capture());
    assertThat(n.getValue().template()).isEqualTo("DIEM_NGHEN_CHUYEN_CAP_TREN");
    assertThatThrownBy(
            () ->
                service.escalateBlocker(
                    TENANT, TASK, BLOCKER, 2, null, null, "ngắn", actor(ASSIGNER)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void resolveCloseAndEdgeCases() {
    UUID handler = UUID.randomUUID();
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN)));
    when(blockers.find(TENANT, 20264, TASK, BLOCKER))
        .thenReturn(Optional.of(blocker("DANG_XU_LY", handler)));
    when(blockers.change(eq(TENANT), eq(20264), eq(BLOCKER), eq(2L), any(), any()))
        .thenReturn(true);
    // Người xử lý ghi kết quả: DA_GIAI_QUYET, có resolved_at, báo người báo.
    service.handleBlocker(
        TENANT,
        TASK,
        BLOCKER,
        2,
        new TaskBlockerService.Patch(
            false, null, true, LocalDate.of(2026, 11, 5), "DA_GIAI_QUYET", "Đã có số liệu"),
        actor(handler));
    ArgumentCaptor<TaskBlockers.Change> c = ArgumentCaptor.forClass(TaskBlockers.Change.class);
    verify(blockers).change(eq(TENANT), eq(20264), eq(BLOCKER), eq(2L), c.capture(), any());
    assertThat(c.getValue().resolvedAt()).isNotNull();
    assertThat(c.getValue().resolveBy()).isEqualTo(LocalDate.of(2026, 11, 5));
    assertThat(c.getValue().resolution()).isEqualTo("Đã có số liệu");
    ArgumentCaptor<BlockerNotices.Notice> n = ArgumentCaptor.forClass(BlockerNotices.Notice.class);
    verify(notices).notice(n.capture());
    assertThat(n.getValue().template()).isEqualTo("DIEM_NGHEN_DA_XU_LY");

    var badStatus = new TaskBlockerService.Patch(false, null, false, null, "SAI", null);
    assertThatThrownBy(
            () -> service.handleBlocker(TENANT, TASK, BLOCKER, 2, badStatus, actor(handler)))
        .isInstanceOf(BusinessException.class);
    var longNote = new TaskBlockerService.Patch(false, null, false, null, null, "x".repeat(2001));
    assertThatThrownBy(
            () -> service.handleBlocker(TENANT, TASK, BLOCKER, 2, longNote, actor(handler)))
        .isInstanceOf(BusinessException.class);
    var toProgress = new TaskBlockerService.Patch(true, null, false, null, "DANG_XU_LY", null);
    when(blockers.find(TENANT, 20264, TASK, BLOCKER)).thenReturn(Optional.of(blocker("MO", null)));
    assertThatThrownBy(
            () -> service.handleBlocker(TENANT, TASK, BLOCKER, 2, toProgress, actor(ASSIGNER)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errors())
                    .extracting(f -> f.field())
                    .containsExactly("handlerPersonId"));
    // Ghi lệch phiên bản giữa chừng.
    when(blockers.change(eq(TENANT), eq(20264), eq(BLOCKER), eq(2L), any(), any()))
        .thenReturn(false);
    var close = new TaskBlockerService.Patch(false, null, false, null, "DONG", "Không còn cần");
    assertThatThrownBy(
            () -> service.handleBlocker(TENANT, TASK, BLOCKER, 2, close, actor(ASSIGNER)))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void escalateAndReportGuards() {
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN)));
    when(blockers.find(TENANT, 20264, TASK, BLOCKER))
        .thenReturn(Optional.of(blocker("DONG", null)));
    assertThatThrownBy(
            () ->
                service.escalateBlocker(
                    TENANT, TASK, BLOCKER, 2, HEAD, null, "Cần hỗ trợ", actor(ASSIGNER)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("TASK-409-INVALID-STATE"));
    assertThatThrownBy(
            () ->
                service.escalateBlocker(
                    TENANT, TASK, BLOCKER, 9, HEAD, null, "Cần hỗ trợ", actor(ASSIGNER)))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(
            () ->
                service.escalateBlocker(
                    TENANT, TASK, BLOCKER, 2, HEAD, null, "Cần hỗ trợ", actor(OUTSIDER)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    when(blockers.find(TENANT, 20264, TASK, BLOCKER)).thenReturn(Optional.of(blocker("MO", null)));
    assertThatThrownBy(
            () ->
                service.escalateBlocker(
                    TENANT, TASK, BLOCKER, 2, null, null, "Cần hỗ trợ", actor(ASSIGNER)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                service.escalateBlocker(
                    TENANT, TASK, BLOCKER, 2, null, null, null, actor(ASSIGNER)))
        .isInstanceOf(BusinessException.class);

    doThrow(
            new vn.viettel.okrkpi.config.api.PeriodLockedException(
                vn.viettel.okrkpi.shared.kernel.PeriodId.of(UUID.randomUUID())))
        .when(periods)
        .assertWritable(any(), any());
    assertThatThrownBy(
            () ->
                service.escalateBlocker(
                    TENANT, TASK, BLOCKER, 2, HEAD, null, "Cần hỗ trợ", actor(ASSIGNER)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("TASK-409-PERIOD-LOCKED"));
    assertThatThrownBy(() -> service.reportBlocker(TENANT, TASK, null, actor(LEAD)))
        .isInstanceOf(BusinessException.class);
    reset(periods);
    assertThatThrownBy(() -> service.reportBlocker(TENANT, TASK, null, actor(LEAD)))
        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errors()).hasSize(4));
    var late =
        new TaskBlockerService.Report(
            "Thiếu", "KHAC", "CHU_QUAN", "THAP", LocalDate.of(2030, 1, 1));
    assertThatThrownBy(() -> service.reportBlocker(TENANT, TASK, late, actor(LEAD)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errors()).extracting(f -> f.field()).containsExactly("resolveBy"));
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.reportBlocker(TENANT, TASK, late, actor(LEAD)))
        .isInstanceOf(java.util.NoSuchElementException.class);
  }

  @Test
  void selfAssignedHandlerGetsNoNotice() {
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DA_GIAO)));
    when(blockers.find(TENANT, 20264, TASK, BLOCKER))
        .thenReturn(Optional.of(blocker("MO", null)), Optional.of(blocker("DANG_XU_LY", ASSIGNER)));
    when(blockers.change(eq(TENANT), eq(20264), eq(BLOCKER), eq(2L), any(), any()))
        .thenReturn(true);
    service.handleBlocker(
        TENANT,
        TASK,
        BLOCKER,
        2,
        new TaskBlockerService.Patch(true, ASSIGNER, false, null, null, null),
        actor(ASSIGNER));
    verify(notices, never()).notice(any());
    assertThat(service.listOf(TENANT, task(TaskStatus.DA_GIAO))).isEmpty();
  }

  @Test
  void rules() {
    assertThat(BlockerRules.check("DONG", "MO", null, null))
        .isEqualTo(BlockerRules.Violation.INVALID_TRANSITION);
    assertThat(BlockerRules.check("DONG", null, null, null))
        .isEqualTo(BlockerRules.Violation.INVALID_TRANSITION);
    assertThat(BlockerRules.check("MO", "DANG_XU_LY", null, null))
        .isEqualTo(BlockerRules.Violation.HANDLER_REQUIRED);
    assertThat(BlockerRules.check("DANG_XU_LY", "DA_GIAI_QUYET", "h", " "))
        .isEqualTo(BlockerRules.Violation.NOTE_REQUIRED);
    assertThat(BlockerRules.check("DA_GIAI_QUYET", "DONG", null, null)).isNull();
    assertThat(BlockerRules.check("MO", "MO", null, null)).isNull();
    assertThat(BlockerRules.afterHandler("MO", "h")).isEqualTo("DANG_XU_LY");
    assertThat(BlockerRules.afterHandler("DANG_XU_LY", null)).isEqualTo("DANG_XU_LY");
    assertThat(BlockerRules.code("NV-2026-0157", 3)).isEqualTo("BN-0157-03");
    assertThat(BlockerRules.code(null, 1)).isEqualTo("BN--01");
  }
}
