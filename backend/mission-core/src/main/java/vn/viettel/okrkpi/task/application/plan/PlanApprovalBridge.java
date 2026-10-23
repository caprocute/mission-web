package vn.viettel.okrkpi.task.application.plan;

import java.util.Objects;
import java.util.Optional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.task.api.PlanVersionApprovedEvent;
import vn.viettel.okrkpi.task.api.PlanVersionReturnedEvent;
import vn.viettel.okrkpi.task.api.PlanVersionSubmittedEvent;
import vn.viettel.okrkpi.task.application.plan.port.PlanApprovalProcess;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.VersionRow;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus;

/**
 * Cầu nối kế hoạch công tác với tiến trình {@code duyet-ke-hoach-quy} của SVC-06 (QT-04 bước 5 và
 * 7, CORE-02-08; cơ chế cầu nối của CORE-01-05). Gửi duyệt thì khởi tạo tiến trình và ghi mã tiến
 * trình vào {@code task.plan_version.wf_process_instance_id}; khi lõi đã trả lại hoặc duyệt trên
 * màn hình nghiệp vụ thì kết thúc tiến trình còn chờ người duyệt. Khoá nghiệp vụ là {@code PLAN:
 * <planId>} theo mẫu luồng của DV-06-03.
 */
public class PlanApprovalBridge {

  /** Luồng mặc định khi kỳ chưa ghim Policy Pack (thứ tự của {@link PlanService#FLOWS}). */
  static final String DEFAULT_FLOW = PlanService.FLOWS.get(0);

  private final PlanRepository plans;
  private final PlanService planService;
  private final PeriodApi periods;
  private final PlanApprovalProcess process;

  public PlanApprovalBridge(
      PlanRepository plans,
      PlanService planService,
      PeriodApi periods,
      PlanApprovalProcess process) {
    this.plans = Objects.requireNonNull(plans, "plans");
    this.planService = Objects.requireNonNull(planService, "planService");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.process = Objects.requireNonNull(process, "process");
  }

  /**
   * Khởi tạo tiến trình cho phiên bản vừa gửi duyệt; bỏ qua khi phiên bản không còn chờ duyệt (đã
   * trả lại hoặc đã duyệt trước khi sự kiện được xử lý). Trả mã tiến trình nếu có.
   */
  public Optional<String> onSubmitted(PlanVersionSubmittedEvent e) {
    Optional<PlanRow> plan = plans.findPlan(e.tenantId(), e.planId());
    if (plan.isEmpty()) {
      return Optional.empty();
    }
    Optional<VersionRow> version =
        plans.versions(e.tenantId(), plan.get().periodBucket(), e.planId()).stream()
            .filter(v -> v.id().equals(e.versionId()))
            .findFirst();
    if (version.isEmpty() || version.get().status() != PlanVersionStatus.CHO_DUYET) {
      return Optional.empty();
    }
    String flow = planService.groups(e.tenantId(), plan.get()).flow();
    Optional<String> pid =
        process.start(
            e.tenantId(),
            new PlanApprovalProcess.Start(
                e.planId(),
                e.versionNo(),
                e.aggregateVersion(),
                e.ownerUnitId(),
                flow == null ? DEFAULT_FLOW : flow,
                e.submittedBy(),
                e.periodId(),
                title(e)));
    pid.ifPresent(
        id ->
            plans.linkProcessInstance(e.tenantId(), plan.get().periodBucket(), e.versionId(), id));
    return pid;
  }

  /** Lõi đã trả lại: kết thúc tiến trình còn chờ người duyệt. */
  public int onReturned(PlanVersionReturnedEvent e) {
    return process.cancel(e.tenantId(), e.planId(), "Kế hoạch đã được trả lại");
  }

  /** Lõi đã duyệt: kết thúc tiến trình còn chờ người duyệt (nếu duyệt ngoài luồng). */
  public int onApproved(PlanVersionApprovedEvent e) {
    return process.cancel(e.tenantId(), e.planId(), "Kế hoạch đã được duyệt");
  }

  private String title(PlanVersionSubmittedEvent e) {
    String period =
        e.periodId() == null
            ? null
            : periods
                .findById(e.tenantId(), PeriodId.of(e.periodId()))
                .map(PeriodSnapshot::name)
                .orElse(null);
    String base = "Duyệt kế hoạch công tác";
    String t = period == null ? base : base + " " + period;
    return e.versionNo() > 1 ? t + " (phiên bản " + e.versionNo() + ")" : t;
  }
}
