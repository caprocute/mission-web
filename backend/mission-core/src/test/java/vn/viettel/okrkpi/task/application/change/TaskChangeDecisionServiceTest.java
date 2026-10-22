package vn.viettel.okrkpi.task.application.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.change.TaskChangeDecisionService.Decision;
import vn.viettel.okrkpi.task.application.change.port.ChangeRequestNotices;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests.ChangeRow;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDeliverables;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.StateLogRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskStatusEvents;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskCommand;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

class TaskChangeDecisionServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID TASK = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID ASSIGNER = UUID.randomUUID();
  static final UUID LEAD = UUID.randomUUID();
  static final UUID BOSS = UUID.randomUUID();
  static final UUID REQ = UUID.randomUUID();
  static final String NOTE = "Đơn vị phối hợp chậm gửi số liệu";
  // Thứ Năm 22/10/2026 theo giờ Việt Nam.
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneOffset.UTC);

  TaskChangeRequests changes = mock(TaskChangeRequests.class);
  TaskLifecycleRepository tasks = mock(TaskLifecycleRepository.class);
  TaskTransitionService transitions = mock(TaskTransitionService.class);
  TaskStatusEvents events = mock(TaskStatusEvents.class);
  PeriodApi periods = mock(PeriodApi.class);
  TaskDeliverables deliverables = mock(TaskDeliverables.class);
  ChangeRequestNotices notices = mock(ChangeRequestNotices.class);
  TaskChangeDecisionService service = service(null);

  TaskChangeDecisionService service(WorkingCalendarApi calendar) {
    return new TaskChangeDecisionService(
        changes, tasks, transitions, events, periods, calendar, deliverables, notices, CLOCK);
  }

  @BeforeEach
  void setUp() {
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN)));
    when(tasks.lock(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN)));
    when(changes.decide(eq(TENANT), eq(20264), eq(REQ), any(), any(), any(), any(), any()))
        .thenReturn(true);
    when(changes.applyToTask(eq(TENANT), eq(20264), eq(TASK), eq(4L), any())).thenReturn(5L);
  }

  static TaskRow task(TaskStatus status) {
    return new TaskRow(
        TASK,
        PERIOD,
        20264,
        "NV-2026-Q4-0001",
        "Báo cáo quý",
        null,
        "THUONG_XUYEN",
        "KE_HOACH",
        null,
        ASSIGNER,
        null,
        null,
        LocalDate.of(2026, 10, 30),
        null,
        null,
        BigDecimal.TEN,
        BigDecimal.ONE,
        "báo cáo",
        null,
        null,
        null,
        status,
        false,
        null,
        "v1",
        4);
  }

  static ChangeRow row(String type, String status, boolean escalated, UUID decidedBy) {
    return new ChangeRow(
        REQ,
        TASK,
        type,
        status,
        LocalDate.of(2026, 11, 6),
        BigDecimal.valueOf(3),
        "Báo cáo mới",
        "KHACH_QUAN",
        null,
        NOTE,
        ASSIGNER,
        LEAD,
        Instant.parse("2026-10-21T03:00:00Z"),
        null,
        decidedBy,
        null,
        null,
        escalated,
        0);
  }

  void pending(String type, boolean escalated) {
    when(changes.find(TENANT, 20264, TASK, REQ))
        .thenReturn(Optional.of(row(type, "CHO_DUYET", escalated, null)));
  }

  static TaskActor manager(UUID id) {
    return new TaskActor(id, Set.of("manager"));
  }

  static Decision approve(String cause) {
    return new Decision(true, cause, NOTE);
  }

  static String code(Throwable e) {
    return ((BusinessException) e).errorCode().code();
  }

  TaskChangeRequests.TaskChange applied() {
    ArgumentCaptor<TaskChangeRequests.TaskChange> c =
        ArgumentCaptor.forClass(TaskChangeRequests.TaskChange.class);
    verify(changes).applyToTask(eq(TENANT), eq(20264), eq(TASK), eq(4L), c.capture());
    return c.getValue();
  }

  @Test
  void objectiveExtensionMovesBothDueDates() {
    pending("GIA_HAN", false);
    service.decide(TENANT, TASK, REQ, 0L, approve("KHACH_QUAN"), manager(ASSIGNER));
    assertThat(applied().extendedDueDate()).isEqualTo(LocalDate.of(2026, 11, 6));
    assertThat(applied().scoreDueDate()).isEqualTo(LocalDate.of(2026, 11, 6));
    verify(events).statusChanged(any());
    ArgumentCaptor<ChangeRequestNotices.Notice> n =
        ArgumentCaptor.forClass(ChangeRequestNotices.Notice.class);
    verify(notices).notice(n.capture());
    assertThat(n.getValue().recipients()).containsExactly(LEAD);
    assertThat(n.getValue().template()).isEqualTo("DE_NGHI_DA_DUYET");
  }

  @Test
  void subjectiveExtensionKeepsScoreDueDate() {
    pending("GIA_HAN", false);
    service.decide(TENANT, TASK, REQ, null, approve("CHU_QUAN"), manager(ASSIGNER));
    assertThat(applied().extendedDueDate()).isEqualTo(LocalDate.of(2026, 11, 6));
    assertThat(applied().scoreDueDate()).isEqualTo(LocalDate.of(2026, 10, 30));
  }

  @Test
  void quantityAndProductChanges() {
    pending("DOI_SO_LUONG", false);
    service.decide(TENANT, TASK, REQ, null, approve("KHACH_QUAN"), manager(ASSIGNER));
    assertThat(applied().plannedQuantity()).isEqualByComparingTo("3");
    pending("DOI_SAN_PHAM", false);
    when(changes.applyToTask(eq(TENANT), eq(20264), eq(TASK), eq(4L), any())).thenReturn(-1L);
    assertThatThrownBy(
            () -> service.decide(TENANT, TASK, REQ, null, approve("KHACH_QUAN"), manager(ASSIGNER)))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void suspendAndCancelGoThroughStateMachine() {
    pending("TAM_DUNG", false);
    when(transitions.transition(eq(TENANT), eq(TASK), any(), any(), isNull()))
        .thenReturn(new TaskTransitionService.Transitioned(task(TaskStatus.TAM_DUNG), null));
    service.decide(TENANT, TASK, REQ, null, approve("KHACH_QUAN"), manager(ASSIGNER));
    ArgumentCaptor<TaskCommand> cmd = ArgumentCaptor.forClass(TaskCommand.class);
    verify(transitions).transition(eq(TENANT), eq(TASK), cmd.capture(), any(), isNull());
    assertThat(cmd.getValue()).isInstanceOf(TaskCommand.Suspend.class);

    pending("HUY", false);
    when(deliverables.of(TENANT, TASK)).thenReturn(List.of(Map.of("status", "DA_XAC_NHAN")));
    assertThatThrownBy(
            () -> service.decide(TENANT, TASK, REQ, null, approve("CHU_QUAN"), manager(ASSIGNER)))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.msg()).isEqualTo("MSG-06-09"));
    when(deliverables.of(TENANT, TASK)).thenReturn(List.of());
    service.decide(TENANT, TASK, REQ, null, approve("CHU_QUAN"), manager(ASSIGNER));
  }

  @Test
  void rejectionsAndGuards() {
    pending("GIA_HAN", false);
    assertThatThrownBy(
            () -> service.decide(TENANT, TASK, REQ, null, approve("KHACH_QUAN"), manager(LEAD)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-403-SAME-AUTHOR"));
    assertThatThrownBy(
            () -> service.decide(TENANT, TASK, REQ, null, approve("KHACH_QUAN"), manager(BOSS)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    assertThatThrownBy(
            () ->
                service.decide(
                    TENANT,
                    TASK,
                    REQ,
                    null,
                    new Decision(false, "CHU_QUAN", " "),
                    manager(ASSIGNER)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () ->
                service.decide(
                    TENANT, TASK, REQ, null, new Decision(true, null, NOTE), manager(ASSIGNER)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> service.decide(TENANT, TASK, REQ, null, null, manager(ASSIGNER)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(
            () -> service.decide(TENANT, TASK, REQ, 7L, approve("KHACH_QUAN"), manager(ASSIGNER)))
        .isInstanceOf(VersionConflictException.class);
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(any(), any());
    assertThatThrownBy(
            () -> service.decide(TENANT, TASK, REQ, null, approve("KHACH_QUAN"), manager(ASSIGNER)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-PERIOD-LOCKED"));
    verify(changes, never()).decide(any(), anyInt(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void escalatedOnlyLeaderAboveAssignerDecides() {
    pending("GIA_HAN", true);
    TaskActor assignerLeader = new TaskActor(ASSIGNER, Set.of("manager", "leader"));
    assertThatThrownBy(
            () -> service.decide(TENANT, TASK, REQ, null, approve("KHACH_QUAN"), assignerLeader))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.detail()).contains("cấp trên"));
    assertThatThrownBy(
            () -> service.decide(TENANT, TASK, REQ, null, approve("KHACH_QUAN"), manager(BOSS)))
        .isInstanceOf(BusinessException.class);
    TaskActor boss = new TaskActor(BOSS, Set.of("leader"));
    service.decide(TENANT, TASK, REQ, null, new Decision(false, "CHU_QUAN", NOTE), boss);
    verify(changes, never()).applyToTask(any(), anyInt(), any(), anyLong(), any());
    ArgumentCaptor<ChangeRequestNotices.Notice> n =
        ArgumentCaptor.forClass(ChangeRequestNotices.Notice.class);
    verify(notices).notice(n.capture());
    assertThat(n.getValue().template()).isEqualTo("DE_NGHI_TU_CHOI");
  }

  @Test
  void replayAppliesOnce() {
    when(changes.find(TENANT, 20264, TASK, REQ))
        .thenReturn(Optional.of(row("GIA_HAN", "DA_DUYET", true, BOSS)));
    TaskActor boss = new TaskActor(BOSS, Set.of("leader"));
    var r = service.decide(TENANT, TASK, REQ, null, approve("KHACH_QUAN"), boss);
    assertThat(r.request().status()).isEqualTo("DA_DUYET");
    verify(changes, never()).decide(any(), anyInt(), any(), any(), any(), any(), any(), any());
    assertThatThrownBy(
            () ->
                service.decide(
                    TENANT, TASK, REQ, null, new Decision(false, "CHU_QUAN", NOTE), boss))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-INVALID-STATE"));
    when(changes.find(TENANT, 20264, TASK, REQ))
        .thenReturn(Optional.of(row("GIA_HAN", "CHO_DUYET", false, null)));
    when(changes.decide(eq(TENANT), eq(20264), eq(REQ), any(), any(), any(), any(), any()))
        .thenReturn(false);
    assertThatThrownBy(
            () -> service.decide(TENANT, TASK, REQ, null, approve("KHACH_QUAN"), manager(ASSIGNER)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-INVALID-STATE"));
  }

  @Test
  void resumeShiftsDueByPausedWorkingDays() {
    // Tạm dừng thứ Sáu 16/10, tiếp tục thứ Năm 22/10: 4 ngày làm việc (19, 20, 21, 22).
    when(tasks.stateLog(TENANT, 20264, TASK))
        .thenReturn(
            List.of(
                new StateLogRow(
                    Instant.parse("2026-10-16T02:00:00Z"),
                    TaskStatus.DANG_THUC_HIEN,
                    TaskStatus.TAM_DUNG,
                    "SUSPEND",
                    ASSIGNER,
                    NOTE,
                    "v1")));
    when(transitions.transition(eq(TENANT), eq(TASK), any(TaskCommand.Resume.class), any(), eq(4L)))
        .thenReturn(new TaskTransitionService.Transitioned(task(TaskStatus.DANG_THUC_HIEN), null));
    service.resume(TENANT, TASK, 4, manager(ASSIGNER));
    assertThat(applied().extendedDueDate()).isEqualTo(LocalDate.of(2026, 11, 5));
    assertThat(applied().scoreDueDate()).isEqualTo(LocalDate.of(2026, 11, 5));
  }

  @Test
  void resumeWithCalendarAndNoPause() {
    WorkingCalendarApi cal = mock(WorkingCalendarApi.class);
    when(cal.countWorkingDays(eq(TENANT), any(), any())).thenReturn(2L);
    when(cal.addWorkingDays(eq(TENANT), any(), eq(2))).thenReturn(LocalDate.of(2026, 11, 4));
    when(tasks.stateLog(TENANT, 20264, TASK))
        .thenReturn(
            List.of(
                new StateLogRow(
                    Instant.parse("2026-10-20T02:00:00Z"),
                    TaskStatus.DANG_THUC_HIEN,
                    TaskStatus.TAM_DUNG,
                    "SUSPEND",
                    ASSIGNER,
                    NOTE,
                    "v1")));
    when(transitions.transition(eq(TENANT), eq(TASK), any(), any(), eq(4L)))
        .thenReturn(new TaskTransitionService.Transitioned(task(TaskStatus.DANG_THUC_HIEN), null));
    service(cal).resume(TENANT, TASK, 4, manager(ASSIGNER));
    assertThat(applied().extendedDueDate()).isEqualTo(LocalDate.of(2026, 11, 4));
    when(changes.applyToTask(eq(TENANT), eq(20264), eq(TASK), eq(4L), any())).thenReturn(-1L);
    assertThatThrownBy(() -> service(cal).resume(TENANT, TASK, 4, manager(ASSIGNER)))
        .isInstanceOf(VersionConflictException.class);
    when(tasks.stateLog(TENANT, 20264, TASK)).thenReturn(List.of());
    assertThat(service.resume(TENANT, TASK, 4, manager(ASSIGNER))).isNotNull();
  }
}
