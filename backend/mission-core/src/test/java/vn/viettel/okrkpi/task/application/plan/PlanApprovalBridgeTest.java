package vn.viettel.okrkpi.task.application.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.PlanVersionApprovedEvent;
import vn.viettel.okrkpi.task.api.PlanVersionReturnedEvent;
import vn.viettel.okrkpi.task.api.PlanVersionSubmittedEvent;
import vn.viettel.okrkpi.task.application.plan.port.PlanApprovalProcess;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.VersionRow;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus;

/** Cầu nối gửi duyệt, trả lại, duyệt kế hoạch với tiến trình SVC-06 (CORE-02-08). */
class PlanApprovalBridgeTest {

  private final PlanRepository plans = mock(PlanRepository.class);
  private final PlanService planService = mock(PlanService.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final PlanApprovalProcess process = mock(PlanApprovalProcess.class);
  private final PlanApprovalBridge bridge =
      new PlanApprovalBridge(plans, planService, periods, process);

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID planId = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();
  private final UUID periodId = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private final UUID submitter = UUID.randomUUID();
  private final PlanRow plan =
      new PlanRow(planId, periodId, 202604, "QUY", unit, null, versionId, 3);

  private VersionRow version(PlanVersionStatus status) {
    return new VersionRow(
        versionId, planId, 2, status, false, Instant.now(), null, null, null, null, 4, 7);
  }

  private PlanVersionSubmittedEvent submitted(UUID period) {
    return new PlanVersionSubmittedEvent(
        tenant, planId, versionId, 2, period, unit, submitter, "ab", 7, Instant.now());
  }

  @Test
  void khoiTaoTienTrinhVaGhiMaVaoPhienBan() {
    when(plans.findPlan(tenant, planId)).thenReturn(Optional.of(plan));
    when(plans.versions(tenant, 202604, planId))
        .thenReturn(List.of(version(PlanVersionStatus.CHO_DUYET)));
    when(planService.groups(tenant, plan))
        .thenReturn(new PlanService.Groups(List.of(), null, "DANG"));
    when(periods.findById(tenant, PeriodId.of(periodId)))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(periodId),
                    tenant,
                    "2026-Q4",
                    "Quý IV/2026",
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 12, 31),
                    PeriodStatus.MO)));
    when(process.start(any(), any())).thenReturn(Optional.of("pid-1"));

    assertThat(bridge.onSubmitted(submitted(periodId))).contains("pid-1");

    ArgumentCaptor<PlanApprovalProcess.Start> start =
        ArgumentCaptor.forClass(PlanApprovalProcess.Start.class);
    verify(process).start(org.mockito.ArgumentMatchers.eq(tenant), start.capture());
    assertThat(start.getValue())
        .isEqualTo(
            new PlanApprovalProcess.Start(
                planId,
                2,
                7,
                unit,
                "DANG",
                submitter,
                periodId,
                "Duyệt kế hoạch công tác Quý IV/2026 (phiên bản 2)"));
    verify(plans).linkProcessInstance(tenant, 202604, versionId, "pid-1");
  }

  @Test
  void luongMacDinhVaTieuDeKhiKhongCoKyVaChuaCauHinhWorkflow() {
    when(plans.findPlan(tenant, planId)).thenReturn(Optional.of(plan));
    when(plans.versions(tenant, 202604, planId))
        .thenReturn(List.of(version(PlanVersionStatus.CHO_DUYET)));
    when(planService.groups(tenant, plan))
        .thenReturn(new PlanService.Groups(List.of(), null, null));
    when(process.start(any(), any())).thenReturn(Optional.empty());

    assertThat(bridge.onSubmitted(submitted(null))).isEmpty();

    ArgumentCaptor<PlanApprovalProcess.Start> start =
        ArgumentCaptor.forClass(PlanApprovalProcess.Start.class);
    verify(process).start(any(), start.capture());
    assertThat(start.getValue().flow()).isEqualTo(PlanApprovalBridge.DEFAULT_FLOW);
    assertThat(start.getValue().title()).isEqualTo("Duyệt kế hoạch công tác (phiên bản 2)");
    verify(plans, never()).linkProcessInstance(any(), anyInt(), any(), anyString());
  }

  @Test
  void boQuaKhiKeHoachMatHoacPhienBanKhongConChoDuyet() {
    when(plans.findPlan(tenant, planId)).thenReturn(Optional.empty());
    assertThat(bridge.onSubmitted(submitted(periodId))).isEmpty();

    when(plans.findPlan(tenant, planId)).thenReturn(Optional.of(plan));
    when(plans.versions(tenant, 202604, planId))
        .thenReturn(List.of(version(PlanVersionStatus.TRA_LAI)));
    assertThat(bridge.onSubmitted(submitted(periodId))).isEmpty();

    when(plans.versions(tenant, 202604, planId)).thenReturn(List.of());
    assertThat(bridge.onSubmitted(submitted(periodId))).isEmpty();
    verify(process, never()).start(any(), any());
  }

  @Test
  void ketThucTienTrinhKhiTraLaiHoacDuyet() {
    when(process.cancel(any(), any(), anyString())).thenReturn(1, 0);
    assertThat(
            bridge.onReturned(
                new PlanVersionReturnedEvent(
                    tenant, planId, versionId, 2, submitter, 8, Instant.now())))
        .isEqualTo(1);
    assertThat(
            bridge.onApproved(
                new PlanVersionApprovedEvent(
                    tenant,
                    planId,
                    versionId,
                    2,
                    periodId,
                    "QUY",
                    unit,
                    4,
                    submitter,
                    UUID.randomUUID(),
                    null,
                    9,
                    Instant.now())))
        .isZero();
    verify(process).cancel(tenant, planId, "Kế hoạch đã được trả lại");
    verify(process).cancel(tenant, planId, "Kế hoạch đã được duyệt");
  }
}
