package vn.viettel.okrkpi.task.application.assign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static vn.viettel.okrkpi.task.application.assign.AssignFixture.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.task.application.assign.port.BulkAssignJobs;
import vn.viettel.okrkpi.task.application.assign.port.TaskAssignedEvents;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.AssigneeRow;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.domain.assign.AssignmentRules;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

class TaskAssignServiceTest {

  final AssignFixture f = new AssignFixture();
  final TaskTransitionService transitions = mock(TaskTransitionService.class);
  final TaskAssignedEvents events = mock(TaskAssignedEvents.class);
  final BulkAssignJobs jobs = mock(BulkAssignJobs.class);
  final OkrApi okr = mock(OkrApi.class);
  final WorkingCalendarApi calendar = mock(WorkingCalendarApi.class);
  final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
  final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
  final List<Runnable> queued = new ArrayList<>();

  TaskAssignService service(OkrApi okrApi, WorkingCalendarApi cal) {
    return new TaskAssignService(
        f.drafts,
        f.repo,
        transitions,
        events,
        jobs,
        f.periods,
        okrApi,
        f.org,
        cal,
        t -> List.of("hỗ trợ"),
        publisher,
        tx,
        queued::add,
        CLOCK,
        null);
  }

  TaskAssignServiceTest() {
    when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(transitions.transition(eq(TENANT), any(), any(), any(), anyLong()))
        .thenAnswer(
            i ->
                new TaskTransitionService.Transitioned(
                    task(i.getArgument(1), TaskStatus.DA_GIAO, ASSIGNER), null));
    when(calendar.isWorkingDay(eq(TENANT), any()))
        .thenAnswer(i -> i.<java.time.LocalDate>getArgument(1).getDayOfWeek().getValue() < 6);
  }

  static String code(Throwable e) {
    return ((BusinessException) e).errorCode().code();
  }

  @Test
  void kiemTraTraDanhSachVaSoNgayLamViec() {
    TaskAssignService.CheckResult r =
        service(okr, calendar).check(TENANT, spec("DOT_XUAT", LEAD), MANAGER);
    assertThat(r.items())
        .extracting(AssignmentRules.CheckItem::rule)
        .contains("BR-05-01", "BR-04-03");
    assertThat(r.items()).allMatch(AssignmentRules.CheckItem::passed);
    assertThat(r.workingDays()).isEqualTo(20);
    assertThat(service(null, null).check(TENANT, spec("DOT_XUAT", LEAD), MANAGER).workingDays())
        .isNull();
  }

  @Test
  void giaoNhiemVuChuTriLaNguoi() {
    DraftRow d = f.put("NHAP", spec("DOT_XUAT", LEAD), true, 1);
    UUID co = UUID.randomUUID();
    when(f.repo.assignees(TENANT, 20264, d.id()))
        .thenReturn(
            List.of(
                new AssigneeRow("PERSON", LEAD, "CHU_TRI"),
                new AssigneeRow("PERSON", co, "PHOI_HOP")));
    TaskAssignService.Assigned done = service(okr, calendar).assignTask(TENANT, d.id(), 1, MANAGER);
    assertThat(done.leadId()).isEqualTo(LEAD);
    ArgumentCaptor<TaskAssignedEvents.Assigned> evt =
        ArgumentCaptor.forClass(TaskAssignedEvents.Assigned.class);
    verify(events).assigned(evt.capture());
    assertThat(evt.getValue().title()).isEqualTo(TaskAssignService.RESTRICTED_TITLE);
    assertThat(evt.getValue().notifyIds()).containsExactly(LEAD);
    verify(f.repo).markAssigned(eq(TENANT), eq(20264), eq(d.id()), any());
    verify(publisher).publishEvent(any(Object.class));
  }

  @Test
  void giaoChoDonViThongBaoNguoiDungDau() {
    DraftRow d = f.put("NHAP", spec("DOT_XUAT", null), false, 1);
    UUID head = UUID.randomUUID();
    when(f.repo.assignees(TENANT, 20264, d.id()))
        .thenReturn(List.of(new AssigneeRow("UNIT", UNIT, "CHU_TRI")));
    when(f.org.headsOf(eq(TENANT), eq(UNIT), any())).thenReturn(List.of(head));
    TaskAssignService.Assigned done = service(okr, calendar).assignTask(TENANT, d.id(), 1, MANAGER);
    assertThat(done.leadId()).isEqualTo(UNIT);
    ArgumentCaptor<TaskAssignedEvents.Assigned> evt =
        ArgumentCaptor.forClass(TaskAssignedEvents.Assigned.class);
    verify(events).assigned(evt.capture());
    assertThat(evt.getValue().notifyIds()).containsExactly(head);
  }

  @Test
  void viPhamQuyTacChanTraLoiKemMaQuyTac() {
    DraftRow d = f.put("NHAP", spec("KE_HOACH", null), false, 1);
    assertThatThrownBy(() -> service(okr, calendar).assignTask(TENANT, d.id(), 1, MANAGER))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errors())
                    .extracting(ProblemFieldError::rule)
                    .containsExactly("BR-05-01", "BR-05-04"));
    verify(transitions, never()).transition(any(), any(), any(), any(), anyLong());
  }

  @Test
  void kyKhoaKrChuaDuyetVaChuTriNghiViec() {
    DraftRow d = f.put("NHAP", spec("DOT_XUAT", LEAD), false, 1);
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(f.periods)
        .assertWritable(TENANT, PeriodId.of(PERIOD));
    assertThatThrownBy(() -> service(okr, calendar).assignTask(TENANT, d.id(), 1, MANAGER))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-PERIOD-LOCKED"));

    AssignFixture g = new AssignFixture();
    UUID kr = UUID.randomUUID();
    TaskDraftSpec s = spec("DOT_XUAT", LEAD);
    TaskDraftSpec withKr =
        new TaskDraftSpec(
            PERIOD,
            s.source(),
            null,
            null,
            s.title(),
            s.taskGroup(),
            kr,
            null,
            LEAD,
            null,
            s.dueDate(),
            null,
            null,
            s.productName(),
            s.quantity(),
            s.unit(),
            s.evidenceRequired(),
            s.qualityCriteria(),
            null,
            null,
            s.basis());
    DraftRow k = g.put("NHAP", withKr, false, 1);
    TaskAssignService svc =
        new TaskAssignService(
            g.drafts,
            g.repo,
            transitions,
            events,
            jobs,
            g.periods,
            okr,
            g.org,
            null,
            t -> List.of(),
            publisher,
            tx,
            queued::add,
            CLOCK,
            null);
    when(okr.findKeyResult(TENANT, kr)).thenReturn(Optional.of(keyResult(kr, "NHAP")));
    assertThatThrownBy(() -> svc.assignTask(TENANT, k.id(), 1, MANAGER))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errors())
                    .extracting(ProblemFieldError::field)
                    .containsExactly("keyResultId"));
    when(okr.findKeyResult(TENANT, kr)).thenReturn(Optional.of(keyResult(kr, "DA_DUYET")));
    when(g.org.assignmentsOf(eq(TENANT), eq(LEAD), any())).thenReturn(List.of());
    assertThatThrownBy(() -> svc.assignTask(TENANT, k.id(), 1, MANAGER))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errors())
                    .extracting(ProblemFieldError::field)
                    .containsExactly("leadPersonId"));
  }

  @Test
  void giaoDongLoatMoiDonViMotGiaoDich() {
    UUID ok = UUID.randomUUID();
    UUID broken = UUID.randomUUID();
    UUID crash = UUID.randomUUID();
    when(f.repo.assignees(eq(TENANT), anyInt(), any())).thenAnswer(i -> List.of());
    when(f.org.headsOf(eq(TENANT), any(), any())).thenReturn(List.of(UUID.randomUUID()));
    // Đơn vị thứ hai vi phạm quy tắc khi đơn vị trùng người nghiệm thu mặc định (người giao).
    TaskDraftSpec s = spec("DOT_XUAT", null);
    TaskAssignService svc = service(okr, calendar);
    when(transitions.transition(eq(TENANT), any(), any(), any(), anyLong()))
        .thenAnswer(
            i ->
                new TaskTransitionService.Transitioned(
                    task(i.getArgument(1), TaskStatus.DA_GIAO, ASSIGNER), null))
        .thenThrow(new IllegalStateException("lỗi"));
    String jobId = svc.bulkAssign(TENANT, s, List.of(ok, ASSIGNER, crash, ok), MANAGER);
    verify(jobs).accepted(TENANT, jobId, ASSIGNER, 3);
    queued.forEach(Runnable::run);
    ArgumentCaptor<List<BulkAssignJobs.Failure>> failures = ArgumentCaptor.forClass(List.class);
    verify(jobs).finished(eq(TENANT), eq(jobId), eq(1), failures.capture());
    assertThat(failures.getValue())
        .extracting(BulkAssignJobs.Failure::unitId)
        .containsExactly(ASSIGNER, crash);
    verify(jobs).progress(TENANT, jobId, 3, 3);
    assertThat(broken).isNotNull();
  }

  @Test
  void giaoDongLoatKiemDanhSachDonViVaGianDoan() {
    TaskAssignService svc = service(okr, calendar);
    assertThatThrownBy(() -> svc.bulkAssign(TENANT, spec("DOT_XUAT", null), List.of(), MANAGER))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    assertThatThrownBy(() -> svc.bulkAssign(TENANT, spec("DOT_XUAT", null), null, MANAGER))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    doThrow(new IllegalStateException("x")).when(jobs).progress(any(), any(), anyInt(), anyInt());
    when(f.repo.assignees(eq(TENANT), anyInt(), any())).thenReturn(List.of());
    String jobId = svc.bulkAssign(TENANT, spec("DOT_XUAT", null), List.of(UNIT), MANAGER);
    queued.forEach(Runnable::run);
    verify(jobs).finished(eq(TENANT), eq(jobId), eq(0), any());
  }

  @Test
  void kiemTraCanhBaoKhoiLuongChuTri() {
    vn.viettel.okrkpi.task.application.mine.port.MyTaskQueries queries =
        mock(vn.viettel.okrkpi.task.application.mine.port.MyTaskQueries.class);
    when(queries.openAssignments(eq(TENANT), anyInt(), eq(LEAD), eq(PERIOD)))
        .thenReturn(
            List.of(
                new vn.viettel.okrkpi.task.application.mine.port.MyTaskQueries.OpenAssignment(
                    "CHU_TRI", java.time.LocalDate.of(2026, 11, 30))));
    TaskAssignService svc =
        new TaskAssignService(
            f.drafts,
            f.repo,
            transitions,
            events,
            jobs,
            f.periods,
            okr,
            f.org,
            calendar,
            t -> List.of(),
            publisher,
            tx,
            queued::add,
            CLOCK,
            new vn.viettel.okrkpi.task.application.mine.LeadWorkloads(queries, 1));
    TaskAssignService.CheckResult r = svc.check(TENANT, spec("DOT_XUAT", LEAD), MANAGER);
    assertThat(r.workload().inDueWeek()).isEqualTo(2);
    assertThat(r.items())
        .filteredOn(i -> "BR-05-11".equals(i.rule()))
        .singleElement()
        .satisfies(i -> assertThat(i.passed()).isFalse());
    assertThat(svc.check(TENANT, spec("DOT_XUAT", null), MANAGER).workload()).isNull();
  }

  static OkrApi.KeyResultSnapshot keyResult(UUID id, String objectiveStatus) {
    return new OkrApi.KeyResultSnapshot(
        id,
        TENANT,
        PeriodId.of(PERIOD),
        UUID.randomUUID(),
        objectiveStatus,
        "PERSON",
        null,
        LEAD,
        "KR1",
        "KR",
        BigDecimal.ONE,
        BigDecimal.ZERO,
        END,
        null);
  }
}
