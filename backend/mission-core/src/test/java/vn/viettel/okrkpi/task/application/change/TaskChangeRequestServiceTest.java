package vn.viettel.okrkpi.task.application.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.AssigneeRow;
import vn.viettel.okrkpi.task.application.blocker.port.TaskBlockers;
import vn.viettel.okrkpi.task.application.change.TaskChangeRequestService.Draft;
import vn.viettel.okrkpi.task.application.change.port.ChangeApprovalStarter;
import vn.viettel.okrkpi.task.application.change.port.ChangeRequestNotices;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests.ChangeRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskDeliverables;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.domain.change.ChangeRules;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

class TaskChangeRequestServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID TASK = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID ASSIGNER = UUID.randomUUID();
  static final UUID LEAD = UUID.randomUUID();
  static final UUID CO = UUID.randomUUID();
  static final UUID REQ = UUID.randomUUID();
  static final String REASON = "Đơn vị phối hợp chưa gửi số liệu tổng hợp";
  // Thứ Năm 22/10/2026 theo giờ Việt Nam.
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneOffset.UTC);

  TaskChangeRequests changes = mock(TaskChangeRequests.class);
  TaskLifecycleRepository tasks = mock(TaskLifecycleRepository.class);
  TaskDraftRepository assignees = mock(TaskDraftRepository.class);
  TaskBlockers blockers = mock(TaskBlockers.class);
  PeriodApi periods = mock(PeriodApi.class);
  TaskDeliverables deliverables = mock(TaskDeliverables.class);
  ChangeRequestNotices notices = mock(ChangeRequestNotices.class);
  ChangeApprovalStarter starter = mock(ChangeApprovalStarter.class);
  TaskChangeRequestService service = service(null);

  TaskChangeRequestService service(WorkingCalendarApi calendar) {
    return new TaskChangeRequestService(
        changes,
        tasks,
        assignees,
        blockers,
        periods,
        calendar,
        deliverables,
        notices,
        TaskChangeRequestService.Settings.defaults(),
        CLOCK,
        starter);
  }

  @BeforeEach
  void setUp() {
    when(assignees.assignees(TENANT, 20264, TASK))
        .thenReturn(
            List.of(
                new AssigneeRow("PERSON", LEAD, "CHU_TRI"),
                new AssigneeRow("PERSON", CO, "PHOI_HOP")));
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN, 10, 30)));
    when(periods.findById(TENANT, PeriodId.of(PERIOD)))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(PERIOD),
                    TENANT,
                    "Q4-2026",
                    "Quý IV năm 2026",
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 12, 31),
                    PeriodStatus.values()[0])));
  }

  /** Nhiệm vụ hạn {@code dueDay}/{@code dueMonth}. */
  static TaskRow task(TaskStatus status, int dueMonth, int dueDay) {
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
        LocalDate.of(2026, dueMonth, dueDay),
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

  static Draft extension(LocalDate to, String cause) {
    return new Draft("GIA_HAN", to, null, null, cause, null, REASON);
  }

  static ChangeRow row(String status, UUID creator) {
    return new ChangeRow(
        REQ,
        TASK,
        "GIA_HAN",
        status,
        LocalDate.of(2026, 11, 4),
        null,
        null,
        "KHACH_QUAN",
        null,
        REASON,
        ASSIGNER,
        creator,
        Instant.parse("2026-10-22T03:00:00Z"),
        null,
        null,
        null,
        null,
        false,
        0);
  }

  static TaskActor actor(UUID id) {
    return new TaskActor(id, Set.of("member"));
  }

  static String code(Throwable e) {
    return ((BusinessException) e).errorCode().code();
  }

  @Test
  void previewThreeSubjectiveDaysGivesPoint85() {
    // Hạn 30/10 (Thứ Sáu) → 04/11 (Thứ Tư): 3 ngày làm việc (2, 3, 4/11).
    var p =
        service.previewChangeRequest(
            TENANT, TASK, extension(LocalDate.of(2026, 11, 4), "CHU_QUAN"), actor(LEAD));
    assertThat(p.extraWorkingDays()).isEqualTo(3);
    assertThat(p.subjectiveDelayDays()).isEqualTo(3);
    assertThat(p.subjectiveC()).isEqualByComparingTo("0.85");
    assertThat(p.objectiveC()).isEqualByComparingTo("1");
    assertThat(p.objectiveScoreDueDate()).isEqualTo(LocalDate.of(2026, 11, 4));
    assertThat(p.subjectiveScoreDueDate()).isEqualTo(LocalDate.of(2026, 10, 30));
    assertThat(p.overThreshold()).isFalse();
    assertThat(p.lateSubmission()).isFalse();
  }

  @Test
  void holidayCalendarCountsWorkingDaysAndThreshold() {
    WorkingCalendarApi cal = mock(WorkingCalendarApi.class);
    when(cal.countWorkingDays(eq(TENANT), any(), any())).thenReturn(11L);
    when(changes.approvedExtensions(TENANT, 20264, TASK)).thenReturn(0);
    var p =
        service(cal)
            .previewChangeRequest(
                TENANT, TASK, extension(LocalDate.of(2026, 11, 20), "KHACH_QUAN"), actor(LEAD));
    assertThat(p.extraWorkingDays()).isEqualTo(11);
    assertThat(p.overThreshold()).isTrue();
    when(changes.approvedExtensions(TENANT, 20264, TASK)).thenReturn(2);
    when(cal.countWorkingDays(eq(TENANT), any(), any())).thenReturn(1L);
    assertThat(
            service(cal)
                .previewChangeRequest(
                    TENANT, TASK, extension(LocalDate.of(2026, 11, 2), "KHACH_QUAN"), actor(LEAD))
                .overThreshold())
        .isTrue();
  }

  @Test
  void lateSubmissionCountsDaysBeforeSubmitAsSubjective() {
    // Hạn 19/10 (Thứ Hai), gửi 22/10: 3 ngày làm việc chậm trước khi gửi.
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN, 10, 19)));
    var p =
        service.previewChangeRequest(
            TENANT, TASK, extension(LocalDate.of(2026, 10, 30), "KHACH_QUAN"), actor(LEAD));
    assertThat(p.lateSubmission()).isTrue();
    assertThat(p.objectiveC()).isEqualByComparingTo("0.85");
  }

  @Test
  void submitWritesPendingAndNotifiesAssigner() {
    when(changes.hasPending(TENANT, 20264, TASK, "GIA_HAN")).thenReturn(false);
    when(changes.insert(eq(TENANT), any(), any())).thenReturn(true);
    when(changes.find(eq(TENANT), eq(20264), eq(TASK), any()))
        .thenReturn(Optional.of(row("CHO_DUYET", LEAD)));
    service.submitChangeRequest(
        TENANT, TASK, extension(LocalDate.of(2026, 11, 4), "KHACH_QUAN"), actor(LEAD));
    ArgumentCaptor<TaskChangeRequests.NewChange> c =
        ArgumentCaptor.forClass(TaskChangeRequests.NewChange.class);
    verify(changes).lockTask(TENANT, 20264, TASK);
    verify(changes).insert(eq(TENANT), c.capture(), any());
    assertThat(c.getValue().approverId()).isEqualTo(ASSIGNER);
    assertThat(c.getValue().newValues())
        .containsEntry("extensionNo", 1)
        .containsEntry("extraWorkingDays", 3);
    ArgumentCaptor<ChangeRequestNotices.Notice> n =
        ArgumentCaptor.forClass(ChangeRequestNotices.Notice.class);
    verify(notices).notice(n.capture());
    assertThat(n.getValue().recipients()).containsExactly(ASSIGNER);
    assertThat(n.getValue().template()).isEqualTo("DE_NGHI_CHO_DUYET");
  }

  @Test
  void submitRejections() {
    Draft ok = extension(LocalDate.of(2026, 11, 4), "KHACH_QUAN");
    assertThatThrownBy(() -> service.submitChangeRequest(TENANT, TASK, ok, actor(CO)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    when(changes.hasPending(TENANT, 20264, TASK, "GIA_HAN")).thenReturn(true);
    assertThatThrownBy(() -> service.submitChangeRequest(TENANT, TASK, ok, actor(LEAD)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-INVALID-STATE"));
    when(changes.hasPending(TENANT, 20264, TASK, "GIA_HAN")).thenReturn(false);
    when(changes.insert(eq(TENANT), any(), any())).thenReturn(false);
    assertThatThrownBy(() -> service.submitChangeRequest(TENANT, TASK, ok, actor(LEAD)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-INVALID-STATE"));
    Draft shortReason =
        new Draft(
            "GIA_HAN", LocalDate.of(2026, 11, 4), null, null, "KHACH_QUAN", null, "x".repeat(19));
    assertThatThrownBy(() -> service.submitChangeRequest(TENANT, TASK, shortReason, actor(LEAD)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    Draft outOfPeriod = extension(LocalDate.of(2027, 1, 5), "KHACH_QUAN");
    assertThatThrownBy(() -> service.submitChangeRequest(TENANT, TASK, outOfPeriod, actor(LEAD)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errors()).extracting(f -> f.field()).containsExactly("newDueDate"));
    Draft earlier = extension(LocalDate.of(2026, 10, 29), "KHACH_QUAN");
    assertThatThrownBy(() -> service.submitChangeRequest(TENANT, TASK, earlier, actor(LEAD)))
        .isInstanceOf(BusinessException.class);
    Draft bad = new Draft("SAI", null, null, null, null, UUID.randomUUID(), null);
    when(blockers.find(eq(TENANT), eq(20264), eq(TASK), any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.submitChangeRequest(TENANT, TASK, bad, actor(LEAD)))
        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errors()).hasSize(4));
    assertThatThrownBy(() -> service.submitChangeRequest(TENANT, TASK, null, actor(LEAD)))
        .isInstanceOf(BusinessException.class);
    Draft qty =
        new Draft("DOI_SO_LUONG", null, BigDecimal.valueOf(-1), null, "CHU_QUAN", null, REASON);
    assertThatThrownBy(() -> service.submitChangeRequest(TENANT, TASK, qty, actor(LEAD)))
        .isInstanceOf(BusinessException.class);
    Draft product = new Draft("DOI_SAN_PHAM", null, null, " ", "CHU_QUAN", null, REASON);
    assertThatThrownBy(() -> service.submitChangeRequest(TENANT, TASK, product, actor(LEAD)))
        .isInstanceOf(BusinessException.class);

    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.TAM_DUNG, 10, 30)));
    Draft pause = new Draft("TAM_DUNG", null, null, null, "KHACH_QUAN", null, REASON);
    assertThatThrownBy(() -> service.submitChangeRequest(TENANT, TASK, pause, actor(LEAD)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-INVALID-STATE"));
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.HOAN_THANH, 10, 30)));
    assertThatThrownBy(() -> service.submitChangeRequest(TENANT, TASK, ok, actor(LEAD)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-INVALID-STATE"));
    when(tasks.find(TENANT, TASK)).thenReturn(Optional.of(task(TaskStatus.DANG_THUC_HIEN, 10, 30)));
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(any(), any());
    assertThatThrownBy(() -> service.submitChangeRequest(TENANT, TASK, ok, actor(LEAD)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-PERIOD-LOCKED"));
    verify(changes, never()).find(any(), anyInt(), any(), any());
  }

  @Test
  void cancelBlockedWhenDeliverableConfirmed() {
    when(deliverables.of(TENANT, TASK)).thenReturn(List.of(Map.of("status", "DA_XAC_NHAN")));
    Draft cancel = new Draft("HUY", null, null, null, "CHU_QUAN", null, REASON);
    assertThat(service.previewChangeRequest(TENANT, TASK, cancel, actor(LEAD)).cancelBlocked())
        .isTrue();
    assertThatThrownBy(() -> service.submitChangeRequest(TENANT, TASK, cancel, actor(LEAD)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode().code()).isEqualTo("TASK-409-INVALID-STATE");
              assertThat(e.msg()).isEqualTo("MSG-06-09");
            });
  }

  @Test
  void withdrawOnlyBySenderWhilePending() {
    when(changes.find(TENANT, 20264, TASK, REQ)).thenReturn(Optional.of(row("CHO_DUYET", LEAD)));
    when(changes.withdraw(eq(TENANT), eq(20264), eq(REQ), eq(LEAD), any())).thenReturn(true);
    assertThatThrownBy(() -> service.withdrawChangeRequest(TENANT, TASK, REQ, actor(CO)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
    service.withdrawChangeRequest(TENANT, TASK, REQ, actor(LEAD));
    verify(notices).notice(any());
    when(changes.find(TENANT, 20264, TASK, REQ)).thenReturn(Optional.of(row("DA_DUYET", LEAD)));
    assertThatThrownBy(() -> service.withdrawChangeRequest(TENANT, TASK, REQ, actor(LEAD)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-INVALID-STATE"));
    when(changes.find(TENANT, 20264, TASK, REQ)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.withdrawChangeRequest(TENANT, TASK, REQ, actor(LEAD)))
        .isInstanceOf(NoSuchElementException.class);
    when(changes.approvedExtensions(TENANT, 20264, TASK)).thenReturn(1);
    TaskRow t = task(TaskStatus.DANG_THUC_HIEN, 10, 30);
    assertThat(service.extensions(TENANT, t)).containsExactly(1, 2);
    assertThat(service.listOf(TENANT, t)).isEmpty();
  }

  @Test
  void overThresholdSubmitStartsOneApprovalProcess() {
    when(changes.approvedExtensions(TENANT, 20264, TASK)).thenReturn(2);
    when(changes.insert(eq(TENANT), any(), any())).thenReturn(true);
    when(changes.find(eq(TENANT), eq(20264), eq(TASK), any()))
        .thenReturn(Optional.of(row("CHO_DUYET", LEAD)));
    service.submitChangeRequest(
        TENANT, TASK, extension(LocalDate.of(2026, 11, 4), "KHACH_QUAN"), actor(LEAD));
    verify(starter).start(TENANT, TASK, REQ, ASSIGNER, 0L, LEAD);
    org.mockito.Mockito.doThrow(new IllegalStateException("mạng"))
        .when(starter)
        .start(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
    service.submitChangeRequest(
        TENANT, TASK, extension(LocalDate.of(2026, 11, 4), "KHACH_QUAN"), actor(LEAD));
  }

  @Test
  void rules() {
    assertThat(ChangeRules.coefficient(new BigDecimal("0.05"), 25)).isEqualByComparingTo("0");
    assertThat(ChangeRules.coefficient(new BigDecimal("0.05"), -1)).isEqualByComparingTo("1");
    assertThat(ChangeRules.validReason(null)).isFalse();
    assertThat(ChangeRules.validReason("x".repeat(20))).isTrue();
    assertThat(ChangeRules.validReason("x".repeat(2001))).isFalse();
    assertThat(ChangeRules.overThreshold(3, 1, 2, 10)).isTrue();
    assertThat(ChangeRules.overThreshold(1, 10, 2, 10)).isFalse();
    assertThat(service.workingDays(TENANT, LocalDate.of(2026, 10, 30), null)).isZero();
  }
}
