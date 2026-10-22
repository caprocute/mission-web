package vn.viettel.okrkpi.task.application.plan;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.PlanVersionReturnedEvent;
import vn.viettel.okrkpi.task.api.PlanVersionSubmittedEvent;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.Check;
import vn.viettel.okrkpi.task.application.plan.PlanService.VersionView;
import vn.viettel.okrkpi.task.application.plan.port.PlanNotices;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PendingPlan;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ReviewRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.VersionRow;
import vn.viettel.okrkpi.task.domain.plan.PlanCommand;
import vn.viettel.okrkpi.task.domain.plan.PlanTransition;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStateMachine;

/**
 * Gửi duyệt, trả lại và danh sách chờ duyệt của kế hoạch quý (QT-04 bước 5, 7b; SCR-0402, SCR-0403;
 * CORE-02-08). Gửi duyệt kiểm lại BR-04-02..07 ở máy chủ, khoá nội dung bằng trạng thái {@code
 * CHO_DUYET}, tính {@link PlanContentHash} và phát {@link PlanVersionSubmittedEvent} trong cùng
 * giao dịch để cầu nối workflow khởi tạo {@code duyet-ke-hoach}.
 *
 * <p>Người duyệt lấy theo {@code flowable.wf_binding} khi cầu nối workflow có; tới lúc đó người
 * duyệt là lãnh đạo (người đứng đầu) đơn vị lập kế hoạch theo bản sao tổ chức (suy luận).
 */
public class PlanReviewService {

  static final String OUT_OF_SCOPE = "COMMON-403-OUT-OF-SCOPE";

  private final PlanRepository plans;
  private final PlanService planService;
  private final PeriodApi periods;
  private final OrgDirectoryApi org;
  private final PlanNotices notices;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;

  public PlanReviewService(
      PlanRepository plans,
      PlanService planService,
      PeriodApi periods,
      OrgDirectoryApi org,
      PlanNotices notices,
      ApplicationEventPublisher publisher,
      Clock clock) {
    this.plans = Objects.requireNonNull(plans, "plans");
    this.planService = Objects.requireNonNull(planService, "planService");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.org = org;
    this.notices = Objects.requireNonNull(notices, "notices");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Gửi duyệt từ {@code NHAP} hoặc {@code TRA_LAI}; còn lỗi chặn thì 422 kèm danh sách. */
  @Transactional(PlanService.TX)
  public VersionView submitVersion(
      TenantId tenant, UUID planId, int versionNo, long expectedVersion, UUID actor) {
    PlanRow plan = plans.findPlan(tenant, planId).orElseThrow(NoSuchElementException::new);
    periods.assertWritable(tenant, PeriodId.of(plan.periodId()));
    VersionRow version = locked(tenant, plan, versionNo, expectedVersion, PlanCommand.SUBMIT);
    VersionView view = planService.version(tenant, planId, versionNo);
    List<Check> blocking =
        view.checks().stream().filter(c -> c.level() == PlanChecker.Level.CHAN).toList();
    if (!blocking.isEmpty()) {
      throw new BusinessException(
          PlanService.VALIDATION,
          "Kế hoạch còn " + blocking.size() + " lỗi cần sửa trước khi gửi duyệt",
          blocking.stream().map(PlanReviewService::fieldError).toList());
    }
    byte[] hash = PlanContentHash.of(plan, versionNo, view.items());
    long rowVersion = plans.markSubmitted(tenant, plan, version, hash, actor);
    publisher.publishEvent(
        new PlanVersionSubmittedEvent(
            tenant,
            planId,
            version.id(),
            versionNo,
            plan.periodId(),
            plan.ownerUnitId(),
            actor,
            HexFormat.of().formatHex(hash),
            rowVersion,
            clock.instant()));
    return planService.version(tenant, planId, versionNo);
  }

  /** Người duyệt trả lại phiên bản {@code CHO_DUYET} kèm ý kiến; mở khoá cho người lập. */
  @Transactional(PlanService.TX)
  public VersionView returnVersion(
      TenantId tenant,
      UUID planId,
      int versionNo,
      long expectedVersion,
      String comment,
      UUID actor) {
    if (comment == null || comment.isBlank()) {
      throw new BusinessException(
          PlanService.VALIDATION,
          "Vui lòng ghi ý kiến khi trả lại kế hoạch",
          List.of(new ProblemFieldError("comment", "Bắt buộc nhập ý kiến", null)));
    }
    PlanRow plan = plans.findPlan(tenant, planId).orElseThrow(NoSuchElementException::new);
    periods.assertWritable(tenant, PeriodId.of(plan.periodId()));
    VersionRow version = locked(tenant, plan, versionNo, expectedVersion, PlanCommand.RETURN);
    if (org != null && !org.headsOf(tenant, plan.ownerUnitId(), today()).contains(actor)) {
      throw new BusinessException(OUT_OF_SCOPE, "Chỉ người duyệt kế hoạch được trả lại");
    }
    UUID author = plans.markReturned(tenant, plan, version, comment.strip(), actor);
    PeriodSnapshot period =
        periods
            .findById(tenant, PeriodId.of(plan.periodId()))
            .orElseThrow(NoSuchElementException::new);
    notices.returned(
        tenant,
        new PendingPlan(
            plan.id(), plan.ownerUnitId(), version.id(), author, version.rowVersion() + 1),
        period.name(),
        author);
    publisher.publishEvent(
        new PlanVersionReturnedEvent(
            tenant,
            planId,
            version.id(),
            versionNo,
            actor,
            version.rowVersion() + 1,
            clock.instant()));
    return planService.version(tenant, planId, versionNo);
  }

  /** Một dòng chờ duyệt kèm kỳ. */
  public record Review(ReviewRow row, PeriodSnapshot period) {}

  /** Phiên bản {@code CHO_DUYET} của các đơn vị mà người gọi đứng đầu. */
  @Transactional(transactionManager = PlanService.TX, readOnly = true)
  public List<Review> approvals(TenantId tenant, UUID actor, UUID afterPlanId, int limit) {
    if (org == null) {
      return List.of();
    }
    Set<UUID> units = new LinkedHashSet<>();
    for (AssignmentView a : org.assignmentsOf(tenant, actor, today())) {
      if (a.head()) {
        units.add(a.orgUnitId());
      }
    }
    if (units.isEmpty()) {
      return List.of();
    }
    Map<UUID, PeriodSnapshot> byId = new HashMap<>();
    List<Review> out = new ArrayList<>();
    for (ReviewRow r : plans.awaitingReview(tenant, units, afterPlanId, limit)) {
      PeriodSnapshot p =
          byId.computeIfAbsent(
              r.plan().periodId(),
              id ->
                  periods
                      .findById(tenant, PeriodId.of(id))
                      .orElseThrow(NoSuchElementException::new));
      out.add(new Review(r, p));
    }
    return out;
  }

  private VersionRow locked(
      TenantId tenant, PlanRow plan, int versionNo, long expected, PlanCommand command) {
    VersionRow version =
        plans
            .lockVersion(tenant, plan.periodBucket(), plan.id(), versionNo)
            .orElseThrow(NoSuchElementException::new);
    if (version.rowVersion() != expected) {
      throw new VersionConflictException("PLAN_VERSION", expected, version.rowVersion());
    }
    PlanTransition result = PlanVersionStateMachine.apply(version.status(), command, true);
    if (result instanceof PlanTransition.Rejected r) {
      throw new BusinessException(PlanService.INVALID_TRANSITION, r.message());
    }
    return version;
  }

  private static ProblemFieldError fieldError(Check c) {
    String field = c.lineNo() == null ? "items" : "items[" + (c.lineNo() - 1) + "]";
    return new ProblemFieldError(field, c.message(), c.rule());
  }

  private java.time.LocalDate today() {
    return java.time.LocalDate.now(clock.withZone(PlanDeadlines.ZONE));
  }
}
