package vn.viettel.okrkpi.task.application.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
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
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.port.TaskAssignedEvents;
import vn.viettel.okrkpi.task.application.direct.port.TaskDirections;
import vn.viettel.okrkpi.task.application.direct.port.TaskDirections.Facts;
import vn.viettel.okrkpi.task.application.direct.port.TaskDirections.Lead;
import vn.viettel.okrkpi.task.application.direct.port.TaskDirections.LedTask;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.StateLogRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.application.overdue.port.TaskReminderNotices;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

class TaskDirectionServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID TASK = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID ASSIGNER = UUID.randomUUID();
  static final UUID ACCEPTOR = UUID.randomUUID();
  static final UUID LEAD = UUID.randomUUID();
  static final UUID NEW_LEAD = UUID.randomUUID();
  static final UUID BOSS = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final UUID SUB = UUID.randomUUID();
  static final UUID OTHER = UUID.randomUUID();
  static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");

  private final TaskLifecycleRepository tasks = mock(TaskLifecycleRepository.class);
  private final TaskDirections directions = mock(TaskDirections.class);
  private final OrgDirectoryApi org = mock(OrgDirectoryApi.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final TaskReminderNotices notices = mock(TaskReminderNotices.class);
  private final TaskAssignedEvents assigned = mock(TaskAssignedEvents.class);
  private TaskDirectionService service;

  @BeforeEach
  void setUp() {
    service =
        new TaskDirectionService(
            tasks,
            directions,
            org,
            periods,
            notices,
            assigned,
            Duration.ofHours(24),
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(directions.facts(TENANT, 20264, TASK))
        .thenReturn(Optional.of(new Facts(SUB, null, false, true)));
    when(directions.lead(TENANT, 20264, TASK))
        .thenReturn(
            Optional.of(
                new Lead(UUID.randomUUID(), "CA_NHAN", LEAD, null, LocalDate.of(2026, 7, 1))));
    when(org.assignmentsOf(eq(TENANT), eq(BOSS), any())).thenReturn(List.of(at(BOSS, UNIT)));
    when(org.descendantsOf(eq(TENANT), any(OrgTree.class), eq(UNIT), anyInt()))
        .thenReturn(List.of(SUB));
  }

  static AssignmentView at(UUID person, UUID unit) {
    return new AssignmentView(
        UUID.randomUUID(), person, unit, null, null, "CHINH", "CHINH", false, null, null);
  }

  static TaskRow task(TaskStatus status, long version) {
    return new TaskRow(
        TASK,
        PERIOD,
        20264,
        "NV-2026-0157",
        "Báo cáo quý",
        null,
        "CHUYEN_MON",
        "KE_HOACH",
        null,
        ASSIGNER,
        null,
        ACCEPTOR,
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
        version);
  }

  static TaskActor actor(UUID id, String... roles) {
    return new TaskActor(id, Set.of(roles));
  }

  @Test
  void remindWritesLogAndOneNoticeToLead() {
    when(tasks.lock(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN, 3)));

    service.remindTask(TENANT, TASK, "  Đề nghị khẩn trương  ", actor(BOSS, "leader"));

    ArgumentCaptor<StateLogRow> log = ArgumentCaptor.forClass(StateLogRow.class);
    verify(tasks)
        .appendStateLog(eq(TENANT), eq(PERIOD), eq(20264), eq(TASK), log.capture(), eq("{}"));
    assertThat(log.getValue().transition()).isEqualTo("NHAC_VIEC");
    assertThat(log.getValue().reason()).isEqualTo("Đề nghị khẩn trương");
    ArgumentCaptor<TaskReminderNotices.Notice> n =
        ArgumentCaptor.forClass(TaskReminderNotices.Notice.class);
    verify(notices).notice(n.capture());
    assertThat(n.getValue().recipients()).containsExactly(LEAD);
    assertThat(n.getValue().params()).containsEntry("ghiChu", "Đề nghị khẩn trương");
  }

  @Test
  void remindToUnitLeadGoesToHeadsAndBlankNoteIsDropped() {
    when(tasks.lock(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DA_GIAO, 3)));
    when(directions.lead(TENANT, 20264, TASK))
        .thenReturn(Optional.of(new Lead(UUID.randomUUID(), "DON_VI", null, SUB, null)));
    UUID head = UUID.randomUUID();
    when(org.headsOf(eq(TENANT), eq(SUB), any())).thenReturn(List.of(head));

    service.remindTask(TENANT, TASK, " ", actor(ASSIGNER, "manager"));

    ArgumentCaptor<TaskReminderNotices.Notice> n =
        ArgumentCaptor.forClass(TaskReminderNotices.Notice.class);
    verify(notices).notice(n.capture());
    assertThat(n.getValue().recipients()).containsExactly(head);
    assertThat(n.getValue().params()).doesNotContainKey("ghiChu");
  }

  @Test
  void remindGuards() {
    when(tasks.lock(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN, 3)));
    expect(
        () -> service.remindTask(TENANT, TASK, null, actor(BOSS, "member")),
        "COMMON-403-OUT-OF-SCOPE");
    when(org.assignmentsOf(eq(TENANT), eq(OTHER), any())).thenReturn(List.of(at(OTHER, OTHER)));
    expect(
        () -> service.remindTask(TENANT, TASK, null, actor(OTHER, "leader")),
        "COMMON-403-OUT-OF-SCOPE");
    expect(
        () -> service.remindTask(TENANT, TASK, "x".repeat(501), actor(BOSS, "leader")),
        "COMMON-422-VALIDATION");
    when(directions.remindedSince(
            eq(TENANT),
            eq(20264),
            eq(TASK),
            eq(BOSS),
            eq("NHAC_VIEC"),
            eq(NOW.minus(Duration.ofHours(24)))))
        .thenReturn(true);
    expect(
        () -> service.remindTask(TENANT, TASK, null, actor(BOSS, "leader")), "TASK-429-REMINDED");
    when(tasks.lock(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.HOAN_THANH, 3)));
    expect(
        () -> service.remindTask(TENANT, TASK, null, actor(BOSS, "leader")),
        "TASK-409-INVALID-STATE");
    verify(notices, never()).notice(any());
  }

  @Test
  void remindInLockedPeriodIsRejected() {
    when(tasks.lock(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN, 3)));
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(TENANT, PeriodId.of(PERIOD));
    expect(
        () -> service.remindTask(TENANT, TASK, null, actor(BOSS, "leader")),
        "TASK-409-PERIOD-LOCKED");
  }

  @Test
  void reassignReplacesLeadEmitsEvt04AndTellsOldLead() {
    when(tasks.lock(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN, 3)));
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN, 4)));
    when(org.assignmentsOf(eq(TENANT), eq(NEW_LEAD), any())).thenReturn(List.of(at(NEW_LEAD, SUB)));
    when(directions.bumpVersion(eq(TENANT), eq(20264), eq(TASK), eq(3L), eq(BOSS), any()))
        .thenReturn(4L);

    TaskDirectionService.Reassigned r =
        service.reassignTask(
            TENANT, TASK, 3, NEW_LEAD, " Chuyển người phụ trách ", actor(BOSS, "leader"));

    assertThat(r.oldLead()).isEqualTo(LEAD);
    assertThat(r.task().rowVersion()).isEqualTo(4);
    verify(directions)
        .replaceLead(
            eq(TENANT),
            eq(PERIOD),
            eq(20264),
            eq(TASK),
            any(),
            eq(NEW_LEAD),
            any(),
            eq(BOSS),
            any());
    ArgumentCaptor<TaskAssignedEvents.Assigned> evt =
        ArgumentCaptor.forClass(TaskAssignedEvents.Assigned.class);
    verify(assigned).assigned(evt.capture());
    assertThat(evt.getValue().leadAssigneeId()).isEqualTo(NEW_LEAD);
    assertThat(evt.getValue().aggregateVersion()).isEqualTo(4);
    assertThat(evt.getValue().keyTask()).isTrue();
    ArgumentCaptor<TaskReminderNotices.Notice> n =
        ArgumentCaptor.forClass(TaskReminderNotices.Notice.class);
    verify(notices).notice(n.capture());
    assertThat(n.getValue().recipients()).containsExactly(LEAD);
    assertThat(n.getValue().params()).containsEntry("lyDo", "Chuyển người phụ trách");
  }

  @Test
  void reassignGuards() {
    when(tasks.lock(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN, 3)));
    TaskActor boss = actor(BOSS, "leader");
    assertThatThrownBy(() -> service.reassignTask(TENANT, TASK, 2, NEW_LEAD, "x", boss))
        .isInstanceOf(VersionConflictException.class);
    expect(
        () -> service.reassignTask(TENANT, TASK, 3, NEW_LEAD, " ", boss), "COMMON-422-VALIDATION");
    expect(() -> service.reassignTask(TENANT, TASK, 3, null, "x", boss), "COMMON-422-VALIDATION");
    expectRule(() -> service.reassignTask(TENANT, TASK, 3, LEAD, "x", boss), "BR-05-01");
    expectRule(() -> service.reassignTask(TENANT, TASK, 3, ACCEPTOR, "x", boss), "BR-05-02");
    when(org.assignmentsOf(eq(TENANT), eq(NEW_LEAD), any())).thenReturn(List.of());
    expect(
        () -> service.reassignTask(TENANT, TASK, 3, NEW_LEAD, "x", boss), "COMMON-422-VALIDATION");
    when(org.assignmentsOf(eq(TENANT), eq(NEW_LEAD), any()))
        .thenReturn(List.of(at(NEW_LEAD, OTHER)));
    expect(
        () -> service.reassignTask(TENANT, TASK, 3, NEW_LEAD, "x", boss),
        "COMMON-403-OUT-OF-SCOPE");
    when(directions.bumpVersion(anyTenant(), anyInt(), any(), anyLong(), any(), any()))
        .thenReturn(-1L);
    assertThatThrownBy(
            () -> service.reassignTask(TENANT, TASK, 3, NEW_LEAD, "x", actor(ASSIGNER, "manager")))
        .isInstanceOf(VersionConflictException.class);
    when(tasks.lock(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.HUY, 3)));
    expect(
        () -> service.reassignTask(TENANT, TASK, 3, NEW_LEAD, "x", boss), "TASK-409-INVALID-STATE");
    verify(assigned, never()).assigned(any());
  }

  @Test
  void leadMovedMarksOpenTasksAndNotifiesEachAssignerOnce() {
    UUID person = UUID.randomUUID();
    UUID assignment = UUID.randomUUID();
    LocalDate end = LocalDate.of(2026, 11, 1);
    UUID other = UUID.randomUUID();
    when(org.assignmentsOf(TENANT, person, end)).thenReturn(List.of());
    when(directions.openTasksLedBy(TENANT, person))
        .thenReturn(
            List.of(
                led("NV-1", ASSIGNER, end.plusDays(10)),
                led("NV-2", ASSIGNER, null),
                led("NV-3", other, end.plusDays(1)),
                led("NV-4", ASSIGNER, end.minusDays(1)),
                led("NV-5", null, end)));

    assertThat(service.onAssignmentEnded(TENANT, person, assignment, UNIT, end)).isEqualTo(4);

    verify(tasks, times(4)).appendStateLog(any(), any(), anyInt(), any(), any(), anyString());
    ArgumentCaptor<TaskReminderNotices.Notice> n =
        ArgumentCaptor.forClass(TaskReminderNotices.Notice.class);
    verify(notices, times(2)).notice(n.capture());
    assertThat(n.getAllValues().get(0).recipients()).containsExactly(ASSIGNER);
    assertThat(n.getAllValues().get(0).params())
        .containsEntry("danhSachNhiemVu", "NV-1, NV-2")
        .containsEntry("soNhiemVu", "2");
  }

  @Test
  void leadMovedSkipsWhenStillAtUnitOrNoEndOrLockedPeriod() {
    UUID person = UUID.randomUUID();
    LocalDate end = LocalDate.of(2026, 11, 1);
    assertThat(service.onAssignmentEnded(TENANT, person, UUID.randomUUID(), UNIT, null)).isZero();
    when(org.assignmentsOf(TENANT, person, end)).thenReturn(List.of(at(person, UNIT)));
    assertThat(service.onAssignmentEnded(TENANT, person, UUID.randomUUID(), UNIT, end)).isZero();
    when(org.assignmentsOf(TENANT, person, end)).thenReturn(List.of());
    when(directions.openTasksLedBy(TENANT, person)).thenReturn(List.of(led("NV-1", ASSIGNER, end)));
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(TENANT, PeriodId.of(PERIOD));
    assertThat(service.onAssignmentEnded(TENANT, person, UUID.randomUUID(), UNIT, end)).isZero();
    verify(notices, never()).notice(any());
  }

  private static LedTask led(String code, UUID assigner, LocalDate due) {
    return new LedTask(
        UUID.randomUUID(), PERIOD, 20264, code, "DANG_THUC_HIEN", assigner, due, "v1", 1);
  }

  private static TenantId anyTenant() {
    return any(TenantId.class);
  }

  private static void expect(Runnable call, String code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode().code()).isEqualTo(code));
  }

  private static void expectRule(Runnable call, String rule) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errors()).anySatisfy(f -> assertThat(f.rule()).isEqualTo(rule)));
  }

  @Test
  void thaoTacChiDaoTheoVaiTroPhamViVaTrangThai() {
    TaskDirectionService.DirectionActions boss =
        service.directionActions(TENANT, actor(BOSS, "leader"));
    assertThat(boss.on(ASSIGNER, SUB, TaskStatus.DANG_THUC_HIEN))
        .containsExactly(TaskPermittedActions.NHAC, TaskPermittedActions.GIAO_LAI);
    assertThat(boss.on(ASSIGNER, SUB, TaskStatus.TAM_DUNG))
        .containsExactly(TaskPermittedActions.GIAO_LAI);
    assertThat(boss.on(ASSIGNER, SUB, TaskStatus.HOAN_THANH)).isEmpty();
    assertThat(boss.on(ASSIGNER, OTHER, TaskStatus.DA_GIAO)).isEmpty();
    assertThat(boss.on(ASSIGNER, null, TaskStatus.DA_GIAO)).isEmpty();
    // Phạm vi chỉ tra một lần cho cả trang.
    verify(org, times(1)).assignmentsOf(eq(TENANT), eq(BOSS), any());

    TaskDirectionService.DirectionActions assigner =
        service.directionActions(TENANT, actor(ASSIGNER, "manager"));
    assertThat(assigner.on(ASSIGNER, OTHER, TaskStatus.DA_GIAO))
        .containsExactly(TaskPermittedActions.NHAC, TaskPermittedActions.GIAO_LAI);
    assertThat(
            service
                .directionActions(TENANT, actor(BOSS, "member"))
                .on(BOSS, SUB, TaskStatus.DA_GIAO))
        .isEmpty();
  }

  @Test
  void thaoTacChiDaoTrenMotNhiemVu() {
    assertThat(
            service.directionActionsOn(
                TENANT, task(TaskStatus.DANG_THUC_HIEN, 3), actor(BOSS, "leader")))
        .containsExactly(TaskPermittedActions.NHAC, TaskPermittedActions.GIAO_LAI);
    when(directions.facts(TENANT, 20264, TASK)).thenReturn(Optional.empty());
    assertThat(
            service.directionActionsOn(
                TENANT, task(TaskStatus.DANG_THUC_HIEN, 3), actor(BOSS, "leader")))
        .isEmpty();
  }
}
