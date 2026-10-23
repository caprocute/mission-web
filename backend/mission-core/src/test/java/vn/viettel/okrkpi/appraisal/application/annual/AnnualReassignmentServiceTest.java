package vn.viettel.okrkpi.appraisal.application.annual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.ProposalTarget;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;

/** Đề xuất bố trí lại (CORE-04-11): kiểm cờ, phiên bản, kỳ của việc, người nghiệm thu, nhật ký. */
class AnnualReassignmentServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final UUID ID = UUID.randomUUID();
  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID DECIDER = UUID.randomUUID();
  private static final UUID APPRAISER = UUID.randomUUID();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2030-03-10T20:00:00Z"), ZoneOffset.UTC);
  private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private final AnnualRatingRepository repository = mock(AnnualRatingRepository.class);
  private final TaskApi tasks = mock(TaskApi.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final AuditLogger audit = mock(AuditLogger.class);
  private final AnnualReassignmentService.Caller caller =
      new AnnualReassignmentService.Caller(CALLER, Set.of("appraiser"));

  private static ProposalTarget target(boolean flag, boolean proposed, UUID decider) {
    return new ProposalTarget(
        ID,
        20290,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "DANG",
        flag,
        proposed,
        3,
        APPRAISER,
        decider);
  }

  private static PeriodSnapshot period(String from, String to) {
    return new PeriodSnapshot(
        PeriodId.of(UUID.randomUUID()),
        TENANT,
        "Q",
        "Quý",
        LocalDate.parse(from),
        LocalDate.parse(to),
        PeriodStatus.MO);
  }

  private AnnualReassignmentService service(AuditLogger logger) {
    return new AnnualReassignmentService(repository, tasks, periods, logger, CLOCK, ZONE, 30);
  }

  @Test
  void taoViecHanBaMuoiNgayVaGhiNhatKy() {
    when(repository.lockForProposal(TENANT, ID))
        .thenReturn(Optional.of(target(true, false, DECIDER)));
    PeriodSnapshot q2 = period("2030-04-01", "2030-06-30");
    // Giờ Việt Nam đã sang ngày 11/3, hạn 30 ngày là 10/4 thuộc quý II.
    when(periods.findCovering(TENANT, "QUY", LocalDate.parse("2030-04-10")))
        .thenReturn(Optional.of(q2));
    UUID task = UUID.randomUUID();
    when(tasks.openDirectedTask(eq(TENANT), any())).thenReturn(task);
    when(repository.markRelocationProposed(TENANT, 20290, ID, CALLER)).thenReturn(4L);
    AtomicReference<ProposalTarget> checked = new AtomicReference<>();

    AnnualReassignmentService.Proposal p =
        service(audit).propose(TENANT, ID, 3, caller, checked::set);

    assertThat(p).isEqualTo(new AnnualReassignmentService.Proposal(ID, task, 4));
    assertThat(checked.get().id()).isEqualTo(ID);
    ArgumentCaptor<TaskApi.DirectedTask> captor =
        ArgumentCaptor.forClass(TaskApi.DirectedTask.class);
    verify(tasks).openDirectedTask(eq(TENANT), captor.capture());
    TaskApi.DirectedTask t = captor.getValue();
    assertThat(t.periodId()).isEqualTo(q2.id().value());
    assertThat(t.dueDate()).isEqualTo(LocalDate.parse("2030-04-10"));
    assertThat(t.leadPersonId()).isEqualTo(CALLER);
    assertThat(t.acceptorId()).isEqualTo(DECIDER);
    assertThat(t.basis()).contains("15.6");
    assertThat(t.actorRoles()).containsExactly("appraiser");
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).record(event.capture());
    assertThat(event.getValue().attributes()).containsEntry("taskId", task.toString());
    assertThat(event.getValue().objectId()).isEqualTo(ID.toString());
  }

  @Test
  void chuaCoKyChuaHanThiDungKyHienHanhVaRutHan() {
    when(repository.lockForProposal(TENANT, ID)).thenReturn(Optional.of(target(true, false, null)));
    PeriodSnapshot q1 = period("2030-01-01", "2030-03-31");
    when(periods.findCovering(TENANT, "QUY", LocalDate.parse("2030-04-10")))
        .thenReturn(Optional.empty());
    when(periods.findCovering(TENANT, "QUY", LocalDate.parse("2030-03-11")))
        .thenReturn(Optional.of(q1));
    when(tasks.openDirectedTask(eq(TENANT), any())).thenReturn(UUID.randomUUID());
    service(null).propose(TENANT, ID, 3, caller, r -> {});
    ArgumentCaptor<TaskApi.DirectedTask> captor =
        ArgumentCaptor.forClass(TaskApi.DirectedTask.class);
    verify(tasks).openDirectedTask(eq(TENANT), captor.capture());
    assertThat(captor.getValue().dueDate()).isEqualTo(LocalDate.parse("2030-03-31"));
    assertThat(captor.getValue().acceptorId()).isEqualTo(APPRAISER);

    when(periods.findCovering(eq(TENANT), eq("QUY"), any())).thenReturn(Optional.empty());
    when(periods.findCurrent(TENANT, "QUY")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service(null).propose(TENANT, ID, 3, caller, r -> {}))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("TASK-409-PERIOD-LOCKED"));
  }

  @Test
  void tuChoiKhiLechPhienBanKhongCoCoHoacDaLap() {
    AnnualReassignmentService s = service(audit);
    when(repository.lockForProposal(TENANT, ID))
        .thenReturn(Optional.of(target(true, false, DECIDER)));
    assertThatThrownBy(() -> s.propose(TENANT, ID, 2, caller, r -> {}))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errorCode().code())
                    .isEqualTo(AnnualReassignmentService.VERSION_CONFLICT));
    when(repository.lockForProposal(TENANT, ID))
        .thenReturn(Optional.of(target(false, false, DECIDER)));
    assertThatThrownBy(() -> s.propose(TENANT, ID, 3, caller, r -> {}))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(AnnualReassignmentService.NO_FLAG));
    when(repository.lockForProposal(TENANT, ID))
        .thenReturn(Optional.of(target(true, true, DECIDER)));
    assertThatThrownBy(() -> s.propose(TENANT, ID, 3, caller, r -> {}))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(AnnualReassignmentService.EXISTS));
    when(repository.lockForProposal(TENANT, ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> s.propose(TENANT, ID, 3, caller, r -> {}))
        .isInstanceOf(NoSuchElementException.class);
    verify(tasks, never()).openDirectedTask(any(), any());
  }

  @Test
  void nguoiNghiemThuKhacNguoiChuTri() {
    assertThat(AnnualReassignmentService.acceptorOf(target(true, false, DECIDER), CALLER))
        .isEqualTo(DECIDER);
    assertThat(AnnualReassignmentService.acceptorOf(target(true, false, CALLER), CALLER))
        .isEqualTo(APPRAISER);
    assertThat(AnnualReassignmentService.acceptorOf(target(true, false, null), APPRAISER)).isNull();
    assertThatThrownBy(
            () -> new AnnualReassignmentService(repository, tasks, periods, null, CLOCK, ZONE, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new AnnualReassignmentService.Caller(CALLER, null).roles()).isEmpty();
  }
}
