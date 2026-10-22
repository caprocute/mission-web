package vn.viettel.okrkpi.task.application.plan;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ApprovedItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.Assignee;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.CatalogRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.VersionRow;
import vn.viettel.okrkpi.task.domain.plan.PlanCommand;
import vn.viettel.okrkpi.task.domain.plan.PlanSchedule;
import vn.viettel.okrkpi.task.domain.plan.PlanTransition;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStateMachine;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus;

/**
 * Kế hoạch công tác quý (QT-04, SCR-0402, CORE-02-05): tạo kế hoạch kèm phiên bản 1 {@code NHAP},
 * lưu nháp, sao chép quý trước, gợi ý từ OKR và các truy vấn đọc. Kiểm tra BR-04-02..07, gửi duyệt,
 * duyệt, điều chỉnh do CORE-02-06..10 thêm trên cùng máy trạng thái.
 */
public class PlanService {

  static final String TX = "taskTransactionManager";

  public static final String PLAN_EXISTS = "TASK-409-PLAN-EXISTS";
  public static final String INVALID_TRANSITION = "TASK-409-INVALID-TRANSITION";
  static final String VALIDATION = "COMMON-422-VALIDATION";

  /** Đơn vị tính mặc định của dòng gợi ý từ KR (suy luận). */
  static final String DEFAULT_UNIT = "sản phẩm";

  private final PlanRepository plans;
  private final PeriodApi periods;
  private final OkrApi okr;
  private final OrgDirectoryApi org;
  private final Clock clock;

  public PlanService(
      PlanRepository plans, PeriodApi periods, OkrApi okr, OrgDirectoryApi org, Clock clock) {
    this.plans = Objects.requireNonNull(plans, "plans");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.okr = Objects.requireNonNull(okr, "okr");
    this.org = org;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Kế hoạch kèm kỳ, phiên bản mới nhất, phiên bản hiệu lực và kỳ trước có kế hoạch đã duyệt. */
  public record PlanView(
      PlanRow plan,
      PeriodSnapshot period,
      VersionRow currentVersion,
      Integer effectiveVersionNo,
      PeriodSnapshot previousPeriod,
      int adjustmentsUsed) {}

  /** Phiên bản kèm dòng. */
  public record VersionView(
      PlanRow plan, PeriodSnapshot period, VersionRow version, List<ItemRow> items) {}

  /** Dòng đã duyệt kèm kỳ để dựng mã kế hoạch. */
  public record ApprovedItem(ApprovedItemRow row, PeriodSnapshot period) {}

  @Transactional(TX)
  public PlanView createPlan(TenantId tenant, UUID periodId, UUID unitId, UUID actor) {
    PeriodId pid = PeriodId.of(periodId);
    periods.assertWritable(tenant, pid);
    PeriodSnapshot period = period(tenant, periodId);
    UUID unit = unitId != null ? unitId : assignedUnit(tenant, actor);
    PlanRow row =
        new PlanRow(
            UUID.randomUUID(),
            periodId,
            PlanSchedule.bucketOf(period.startDate()),
            "TAP_THE",
            unit,
            null,
            null,
            0);
    UUID versionId = UUID.randomUUID();
    if (!plans.insertPlan(tenant, row, versionId, actor)) {
      throw new BusinessException(PLAN_EXISTS, "Đơn vị đã có kế hoạch công tác cho kỳ này");
    }
    return view(tenant, plans.findPlan(tenant, row.id()).orElseThrow(), period);
  }

  @Transactional(TX)
  public VersionView saveVersion(
      TenantId tenant,
      UUID planId,
      int versionNo,
      long expectedVersion,
      List<ItemRow> drafts,
      UUID actor) {
    PlanRow plan = plan(tenant, planId);
    periods.assertWritable(tenant, PeriodId.of(plan.periodId()));
    VersionRow version = editable(tenant, plan, versionNo, expectedVersion, PlanCommand.SAVE);
    Map<UUID, CatalogRow> catalog =
        plans.catalogItems(
            tenant, drafts.stream().map(ItemRow::catalogItemId).filter(Objects::nonNull).toList());
    List<ItemRow> items = new ArrayList<>(drafts.size());
    for (ItemRow d : drafts) {
      items.add(numbered(d, items.size() + 1, catalog));
    }
    plans.replaceItems(tenant, plan, version, items, actor);
    return version(tenant, planId, versionNo);
  }

  /**
   * Sao chép dòng thường xuyên và dòng chưa kết thúc của phiên bản hiệu lực quý trước vào phiên bản
   * 1 còn trống; dòng "chưa xong" là dòng chưa có nhiệm vụ {@code HOAN_THANH} hoặc {@code HUY} (suy
   * luận).
   */
  @Transactional(TX)
  public VersionView copyFromPreviousPeriod(
      TenantId tenant,
      UUID planId,
      int versionNo,
      long expectedVersion,
      UUID sourcePeriodId,
      UUID actor) {
    PlanRow plan = plan(tenant, planId);
    periods.assertWritable(tenant, PeriodId.of(plan.periodId()));
    VersionRow version =
        editable(tenant, plan, versionNo, expectedVersion, PlanCommand.COPY_FROM_PREVIOUS);
    if (versionNo != 1 || version.itemCount() > 0) {
      throw new BusinessException(
          INVALID_TRANSITION, "Chỉ sao chép vào phiên bản đầu tiên chưa có công việc");
    }
    PeriodSnapshot target = period(tenant, plan.periodId());
    int sourceBucket =
        sourcePeriodId == null
            ? PlanSchedule.previousBucket(plan.periodBucket())
            : PlanSchedule.bucketOf(period(tenant, sourcePeriodId).startDate());
    Map.Entry<PlanRow, VersionRow> source =
        plans
            .effectiveOf(
                tenant, sourceBucket, sourcePeriodId, plan.ownerUnitId(), plan.ownerPersonId())
            .orElseThrow(
                () ->
                    new BusinessException(
                        INVALID_TRANSITION, "Quý trước chưa có kế hoạch đã duyệt để sao chép"));
    PeriodSnapshot sourcePeriod = period(tenant, source.getKey().periodId());
    List<ItemRow> sourceItems = plans.items(tenant, sourceBucket, source.getValue().id());
    Set<UUID> closed =
        plans.closedItems(tenant, sourceBucket, sourceItems.stream().map(ItemRow::id).toList());
    List<ItemRow> copied = new ArrayList<>();
    for (ItemRow s : sourceItems) {
      if (!"THUONG_XUYEN".equals(s.taskGroup()) && closed.contains(s.id())) {
        continue;
      }
      LocalDate due =
          PlanSchedule.shiftDue(
              s.dueDate(), sourcePeriod.startDate(), target.startDate(), target.endDate());
      copied.add(
          new ItemRow(
              UUID.randomUUID(),
              copied.size() + 1,
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
              due,
              s.id(),
              null,
              s.recurrence()));
    }
    plans.replaceItems(tenant, plan, version, copied, actor);
    return version(tenant, planId, versionNo);
  }

  /**
   * Một dòng gợi ý cho mỗi KR của mục tiêu {@code DA_DUYET} của đơn vị chưa có dòng hay nhiệm vụ.
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<ItemRow> suggestions(TenantId tenant, UUID planId) {
    PlanRow plan = plan(tenant, planId);
    PeriodSnapshot period = period(tenant, plan.periodId());
    Set<UUID> linked =
        plans.linkedKeyResults(tenant, plan.periodBucket(), plan.periodId(), plan.id());
    List<ItemRow> out = new ArrayList<>();
    for (OkrApi.KeyResultSnapshot kr :
        okr.listApprovedKeyResults(tenant, PeriodId.of(plan.periodId()), plan.ownerUnitId())) {
      if (linked.contains(kr.id())) {
        continue;
      }
      LocalDate due = kr.dueDate() == null ? period.endDate() : kr.dueDate();
      out.add(
          new ItemRow(
              null,
              out.size() + 1,
              null,
              null,
              kr.id(),
              kr.title(),
              "THEN_CHOT",
              new Assignee("UNIT", plan.ownerUnitId(), ""),
              List.of(),
              kr.title(),
              BigDecimal.ONE,
              DEFAULT_UNIT,
              null,
              null,
              due.isAfter(period.endDate()) ? period.endDate() : due,
              null,
              null,
              null));
    }
    return out;
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public List<PlanView> listPlans(
      TenantId tenant,
      UUID periodId,
      UUID unitId,
      PlanVersionStatus status,
      UUID after,
      int limit) {
    PeriodSnapshot period =
        periodId == null
            ? periods.findCurrent(tenant, "QUY").orElseThrow(NoSuchElementException::new)
            : period(tenant, periodId);
    return plans
        .listPlans(
            tenant,
            PlanSchedule.bucketOf(period.startDate()),
            period.id().value(),
            unitId,
            status,
            after,
            limit)
        .stream()
        .map(p -> view(tenant, p, period))
        .toList();
  }

  /** Phiên bản của một kế hoạch kèm kỳ. */
  public record Versions(PeriodSnapshot period, List<VersionRow> rows) {}

  @Transactional(transactionManager = TX, readOnly = true)
  public Versions versions(TenantId tenant, UUID planId) {
    PlanRow plan = plan(tenant, planId);
    return new Versions(
        period(tenant, plan.periodId()), plans.versions(tenant, plan.periodBucket(), planId));
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public VersionView version(TenantId tenant, UUID planId, int versionNo) {
    PlanRow plan = plan(tenant, planId);
    VersionRow v =
        plans.versions(tenant, plan.periodBucket(), planId).stream()
            .filter(x -> x.versionNo() == versionNo)
            .findFirst()
            .orElseThrow(NoSuchElementException::new);
    return new VersionView(
        plan, period(tenant, plan.periodId()), v, plans.items(tenant, plan.periodBucket(), v.id()));
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public List<ApprovedItem> approvedItems(
      TenantId tenant,
      UUID periodId,
      PlanVersionStatus status,
      UUID afterPlanId,
      int afterItemNo,
      int limit) {
    PeriodSnapshot period =
        periodId == null
            ? periods.findCurrent(tenant, "QUY").orElseThrow(NoSuchElementException::new)
            : period(tenant, periodId);
    return plans
        .approvedItems(
            tenant,
            PlanSchedule.bucketOf(period.startDate()),
            period.id().value(),
            status == null ? PlanVersionStatus.DA_DUYET : status,
            afterPlanId,
            afterItemNo,
            limit)
        .stream()
        .map(r -> new ApprovedItem(r, period))
        .toList();
  }

  private PlanView view(TenantId tenant, PlanRow plan, PeriodSnapshot period) {
    List<VersionRow> versions = plans.versions(tenant, plan.periodBucket(), plan.id());
    Integer effective =
        PlanVersionStateMachine.effectiveVersion(
                versions.stream()
                    .map(v -> new PlanVersionStateMachine.VersionState(v.versionNo(), v.status()))
                    .toList())
            .orElse(null);
    PeriodSnapshot previous =
        plans
            .effectiveOf(
                tenant,
                PlanSchedule.previousBucket(plan.periodBucket()),
                null,
                plan.ownerUnitId(),
                plan.ownerPersonId())
            .flatMap(e -> periods.findById(tenant, PeriodId.of(e.getKey().periodId())))
            .orElse(null);
    int used = (int) versions.stream().filter(v -> v.versionNo() > 1).count();
    return new PlanView(plan, period, versions.get(0), effective, previous, used);
  }

  private VersionRow editable(
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
      throw new BusinessException(INVALID_TRANSITION, r.message());
    }
    return version;
  }

  private static ItemRow numbered(ItemRow d, int itemNo, Map<UUID, CatalogRow> catalog) {
    CatalogRow c = d.catalogItemId() == null ? null : catalog.get(d.catalogItemId());
    if (d.catalogItemId() != null && c == null) {
      throw new BusinessException(
          VALIDATION,
          "Công việc không có trong danh mục áp dụng",
          List.of(
              new ProblemFieldError(
                  "items[" + (itemNo - 1) + "].catalogItemId",
                  "Mục danh mục không tồn tại",
                  null)));
    }
    String unit = c == null ? d.unit() : c.unit();
    String field = "items[" + (itemNo - 1) + "]";
    if (d.lead() == null || d.lead().id() == null) {
      throw invalid(field + ".leadAssignee", "Chọn một chủ trì duy nhất");
    }
    if (d.quantity() == null || d.quantity().signum() <= 0) {
      throw invalid(field + ".quantity", "Khối lượng phải lớn hơn 0");
    }
    if (unit == null || unit.isBlank()) {
      throw invalid(field + ".unit", "Nhập đơn vị tính");
    }
    // Dòng thuộc danh mục lấy điểm, mức khó, đơn vị tính từ danh mục; dòng ngoài danh mục để trống
    // điểm cho bước duyệt (BR-04-05).
    return new ItemRow(
        d.id() == null ? UUID.randomUUID() : d.id(),
        itemNo,
        d.catalogItemId(),
        c == null ? null : c.code(),
        d.keyResultId(),
        d.title(),
        d.taskGroup(),
        d.lead(),
        d.coAssignees() == null ? List.of() : d.coAssignees(),
        c == null ? d.productName() : c.productName(),
        d.quantity(),
        unit,
        c == null ? null : c.difficulty(),
        c == null ? null : c.standardScore(),
        d.dueDate(),
        d.originItemId(),
        d.changeReason(),
        d.recurrence());
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        VALIDATION, message, List.of(new ProblemFieldError(field, message, null)));
  }

  private PlanRow plan(TenantId tenant, UUID planId) {
    return plans.findPlan(tenant, planId).orElseThrow(NoSuchElementException::new);
  }

  private PeriodSnapshot period(TenantId tenant, UUID periodId) {
    return periods.findById(tenant, PeriodId.of(periodId)).orElseThrow(NoSuchElementException::new);
  }

  private UUID assignedUnit(TenantId tenant, UUID actor) {
    Optional<UUID> unit =
        org == null
            ? Optional.empty()
            : org.assignmentsOf(tenant, actor, LocalDate.now(clock)).stream()
                .map(AssignmentView::orgUnitId)
                .filter(Objects::nonNull)
                .findFirst();
    return unit.orElseThrow(
        () ->
            new BusinessException(
                VALIDATION,
                "Chưa xác định được đơn vị lập kế hoạch",
                List.of(new ProblemFieldError("unitId", "Chọn đơn vị lập kế hoạch", null))));
  }
}
