package vn.viettel.okrkpi.task.application.plan;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.PlanService.VersionView;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.VersionRow;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus;

/**
 * Điều chỉnh kế hoạch giữa quý (QT-04 bước 9, SCR-0402, SCR-0403, CORE-02-10): tạo phiên bản n+1
 * {@code NHAP} từ phiên bản hiệu lực trong giới hạn số lần và trước hạn điều chỉnh (BR-04-10), so
 * sánh hai phiên bản. Phiên bản điều chỉnh đi qua cùng luồng gửi duyệt, ký số của CORE-02-08,
 * CORE-02-09 và hiệu lực từ ngày duyệt, không hồi tố.
 */
public class PlanAdjustmentService {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private final PlanRepository plans;
  private final PlanService planService;
  private final PeriodApi periods;
  private final Clock clock;

  public PlanAdjustmentService(
      PlanRepository plans, PlanService planService, PeriodApi periods, Clock clock) {
    this.plans = Objects.requireNonNull(plans, "plans");
    this.planService = Objects.requireNonNull(planService, "planService");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** So sánh kèm lần điều chỉnh hiện tại và số lần tối đa ({@code PlanDiff}). */
  public record DiffView(
      PlanAdjustments.Diff diff,
      int adjustmentNo,
      int adjustmentsMax,
      boolean groupWeightChanged) {}

  /** Giới hạn điều chỉnh của kế hoạch theo Policy Pack. */
  record Limits(int max, int cutoffMonths) {}

  @Transactional(PlanService.TX)
  public VersionView create(TenantId tenant, UUID planId, String changeReason, UUID actor) {
    if (changeReason == null || changeReason.isBlank()) {
      throw invalid("changeReason", "Nhập lý do điều chỉnh kế hoạch", null);
    }
    PlanRow plan = plans.findPlan(tenant, planId).orElseThrow(NoSuchElementException::new);
    periods.assertWritable(tenant, PeriodId.of(plan.periodId()));
    PeriodSnapshot period =
        periods
            .findById(tenant, PeriodId.of(plan.periodId()))
            .orElseThrow(NoSuchElementException::new);
    List<VersionRow> versions = plans.versions(tenant, plan.periodBucket(), planId);
    if (versions.stream().anyMatch(v -> v.status() != PlanVersionStatus.DA_DUYET)) {
      throw new BusinessException(
          PlanService.INVALID_TRANSITION,
          "Kế hoạch đang có phiên bản chưa được duyệt, hoàn tất phiên bản đó trước khi điều chỉnh");
    }
    VersionRow effective =
        versions.stream()
            .filter(VersionRow::current)
            .findFirst()
            .or(() -> versions.stream().findFirst())
            .orElseThrow(
                () ->
                    new BusinessException(
                        PlanService.INVALID_TRANSITION,
                        "Kế hoạch chưa có phiên bản đã duyệt để điều chỉnh"));
    Limits limits = limits(tenant, plan);
    LocalDate cutoff = PlanAdjustments.cutoff(period.endDate(), limits.cutoffMonths());
    if (!today().isBefore(cutoff)) {
      throw invalid(
          "changeReason",
          "Đã hết hạn điều chỉnh kế hoạch của kỳ (trước ngày " + DATE.format(cutoff) + ").",
          PlanAdjustments.BR_04_10);
    }
    int used = used(versions);
    int allowed = PlanAdjustments.allowed(limits.max(), grantedExceptions(tenant, plan));
    if (used >= allowed) {
      throw invalid(
          "changeReason",
          "Kế hoạch đã điều chỉnh " + used + " lần trong quý, đạt giới hạn.",
          PlanAdjustments.BR_04_10);
    }
    List<ItemRow> copied = new ArrayList<>();
    for (ItemRow s : plans.items(tenant, plan.periodBucket(), effective.id())) {
      copied.add(
          new ItemRow(
              UUID.randomUUID(),
              s.itemNo(),
              s.catalogItemId(),
              s.catalogCode(),
              s.keyResultId(),
              s.title(),
              s.taskGroup(),
              s.lead(),
              s.coAssignees(),
              s.productName(),
              s.quantity(),
              s.unit(),
              s.difficulty(),
              s.standardScore(),
              s.dueDate(),
              s.id(),
              null,
              s.recurrence()));
    }
    int versionNo = versions.get(0).versionNo() + 1;
    plans.insertAdjustment(
        tenant, plan, UUID.randomUUID(), versionNo, changeReason.strip(), copied, actor);
    return planService.version(tenant, planId, versionNo);
  }

  /** {@code base = 0}: so với kế hoạch rỗng, mọi dòng là dòng thêm. */
  @Transactional(transactionManager = PlanService.TX, readOnly = true)
  public DiffView diff(TenantId tenant, UUID planId, int versionNo, int baseVersionNo) {
    PlanRow plan = plans.findPlan(tenant, planId).orElseThrow(NoSuchElementException::new);
    List<VersionRow> versions = plans.versions(tenant, plan.periodBucket(), planId);
    VersionRow target = find(versions, versionNo);
    List<ItemRow> items = plans.items(tenant, plan.periodBucket(), target.id());
    List<ItemRow> baseItems =
        baseVersionNo == 0
            ? List.of()
            : plans.items(tenant, plan.periodBucket(), find(versions, baseVersionNo).id());
    PlanAdjustments.Diff diff =
        PlanAdjustments.diff(
            baseVersionNo,
            versionNo,
            baseItems,
            items,
            plans.removedItems(tenant, plan.periodBucket(), target.id()));
    Limits limits = limits(tenant, plan);
    return new DiffView(
        diff,
        Math.max(0, versionNo - 1),
        PlanAdjustments.allowed(limits.max(), grantedExceptions(tenant, plan)),
        PlanAdjustments.groupWeightChanged(baseItems, items));
  }

  /** Số lần điều chỉnh đã duyệt trong kỳ: phiên bản {@code DA_DUYET} sau phiên bản đầu. */
  static int used(List<VersionRow> versions) {
    return (int)
        versions.stream()
            .filter(v -> v.versionNo() > 1 && v.status() == PlanVersionStatus.DA_DUYET)
            .count();
  }

  /**
   * Số lần ngoại lệ người đứng đầu đã cấp (TL-02a QT-04 "Ngoại lệ và xử lý"). Thao tác {@code
   * grantPlanAdjustmentException} chưa có nơi lưu ở TL-03 nên hiện bằng 0; đây là điểm mở rộng.
   */
  int grantedExceptions(TenantId tenant, PlanRow plan) {
    return 0;
  }

  Limits limits(TenantId tenant, PlanRow plan) {
    return new Limits(
        integer(planService.parameter(tenant, plan, PlanAdjustments.MAX_KEY))
            .orElse(PlanAdjustments.DEFAULT_MAX),
        integer(planService.parameter(tenant, plan, PlanAdjustments.CUTOFF_KEY))
            .filter(n -> n >= 1)
            .orElse(PlanAdjustments.DEFAULT_CUTOFF_MONTHS));
  }

  static Optional<Integer> integer(Optional<Object> value) {
    return value.flatMap(
        v -> {
          if (v instanceof Number n) {
            return Optional.of(n.intValue());
          }
          try {
            return Optional.of(Integer.parseInt(String.valueOf(v).strip()));
          } catch (NumberFormatException e) {
            return Optional.empty();
          }
        });
  }

  private static VersionRow find(List<VersionRow> versions, int versionNo) {
    return versions.stream()
        .filter(v -> v.versionNo() == versionNo)
        .findFirst()
        .orElseThrow(NoSuchElementException::new);
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(PlanDeadlines.ZONE));
  }

  private static BusinessException invalid(String field, String message, String rule) {
    return new BusinessException(
        PlanService.VALIDATION, message, List.of(new ProblemFieldError(field, message, rule)));
  }
}
