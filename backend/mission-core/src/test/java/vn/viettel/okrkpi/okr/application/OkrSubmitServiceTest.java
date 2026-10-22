package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.okr.application.OkrReadService.ObjectiveView;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.Finding;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalStarter;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalStarter.ApprovalStarted;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.ObjectiveHead;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị trình phê duyệt mục tiêu (CORE-01-05). */
class OkrSubmitServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID ACTOR = UUID.randomUUID();
  static final UUID ID = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();

  private final OkrWriteRepository writes = mock(OkrWriteRepository.class);
  private final OkrReadService reads = mock(OkrReadService.class);
  private final OkrCheckService checks = mock(OkrCheckService.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final OkrApprovalStarter starter = mock(OkrApprovalStarter.class);
  private final ObjectiveView view = mock(ObjectiveView.class);
  private final OkrSubmitService service =
      new OkrSubmitService(
          writes,
          reads,
          checks,
          periods,
          () -> starter,
          TransactionOperations.withoutTransaction());

  static ObjectiveHead head(String status, long version) {
    return new ObjectiveHead(ID, PERIOD, 20271, "TAP_THE", UNIT, null, "O1", null, status, version);
  }

  static Finding finding(String rule, String level, UUID kr) {
    return new Finding(rule, "BR-02-01", level, "Thông điệp " + rule, kr, null);
  }

  static String code(Throwable t) {
    return ((BusinessException) t).errorCode().code();
  }

  @BeforeEach
  void setUp() {
    when(writes.lockObjective(TENANT, ID)).thenReturn(Optional.of(head("NHAP", 1)));
    when(periods.findById(TENANT, PeriodId.of(PERIOD)))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(PERIOD),
                    TENANT,
                    "Q1",
                    "Quý I",
                    LocalDate.of(2027, 1, 1),
                    LocalDate.of(2027, 3, 31),
                    PeriodStatus.MO)));
    when(reads.objective(TENANT, ID)).thenReturn(Optional.of(view));
    when(writes.transitionObjective(eq(TENANT), any(), eq("CHO_DUYET"), eq(ACTOR))).thenReturn(2L);
  }

  @AfterEach
  void clearSync() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void trinhHopLeKhoiTaoTienTrinhSauCommit() {
    when(checks.check(TENANT, view)).thenReturn(List.of(finding("R", "CANH_BAO", null)));
    when(starter.start(TENANT, ID, UNIT, 2L, ACTOR))
        .thenReturn(Optional.of(new ApprovalStarted("pi-9", UUID.randomUUID(), null)));
    TransactionSynchronizationManager.initSynchronization();
    assertThat(service.submit(TENANT, ACTOR, ID, 1)).isEqualTo(2L);
    verify(starter, never()).start(any(), any(), any(), anyLong(), any());
    for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
      s.afterCommit();
    }
    verify(writes).attachProcessInstance(TENANT, 20271, ID, "pi-9");
  }

  @Test
  void loiKhoiTaoChiGhiNhatKy() {
    when(checks.check(TENANT, view)).thenReturn(List.of());
    doThrow(new IllegalStateException("workflow-service không phản hồi"))
        .when(starter)
        .start(any(), any(), any(), anyLong(), any());
    assertThat(service.submit(TENANT, ACTOR, ID, 1)).isEqualTo(2L);
    OkrSubmitService noStarter =
        new OkrSubmitService(
            writes, reads, checks, periods, () -> null, TransactionOperations.withoutTransaction());
    assertThat(noStarter.submit(TENANT, ACTOR, ID, 1)).isEqualTo(2L);
    verify(writes, never())
        .attachProcessInstance(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
  }

  @Test
  void loiChanTra422VaMaRiengChoCapTren() {
    UUID kr = UUID.randomUUID();
    when(checks.check(TENANT, view))
        .thenReturn(List.of(finding("R1", OkrRuleChecker.LOI_CHAN, kr)));
    assertThatThrownBy(() -> service.submit(TENANT, ACTOR, ID, 1))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"))
        .satisfies(
            t ->
                assertThat(((BusinessException) t).errors().getFirst().field())
                    .isEqualTo("keyResults." + kr));
    when(checks.check(TENANT, view))
        .thenReturn(
            List.of(
                finding("R1", OkrRuleChecker.LOI_CHAN, null),
                finding(OkrSubmitService.PARENT_RULE, OkrRuleChecker.LOI_CHAN, null)));
    assertThatThrownBy(() -> service.submit(TENANT, ACTOR, ID, 1))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-PARENT-NOT-APPROVED"));
  }

  @Test
  void kiemPhienBanTrangThaiVaKy() {
    assertThatThrownBy(() -> service.submit(TENANT, ACTOR, ID, 0))
        .isInstanceOf(VersionConflictException.class);
    UUID missing = UUID.randomUUID();
    when(writes.lockObjective(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.submit(TENANT, ACTOR, missing, 0))
        .isInstanceOf(OkrComposeService.NotFound.class);
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(eq(TENANT), eq(PeriodId.of(PERIOD)), any());
    assertThatThrownBy(() -> service.submit(TENANT, ACTOR, ID, 1))
        .isInstanceOf(PeriodLockedException.class);
    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.submit(TENANT, ACTOR, ID, 1))
        .isInstanceOf(IllegalStateException.class);
    when(writes.lockObjective(TENANT, ID)).thenReturn(Optional.of(head("CHO_DUYET", 1)));
    assertThatThrownBy(() -> service.submit(TENANT, ACTOR, ID, 1))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-409-INVALID-STATE"));
  }
}
