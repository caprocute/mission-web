package vn.viettel.okrkpi.task.application.plan;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
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
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.catalog.CatalogPolicy;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.Check;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.GroupWeight;
import vn.viettel.okrkpi.task.application.plan.port.PlanLineFacts;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ApprovedItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.Assignee;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.CatalogRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.RemovedItem;
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

  /** Nhiệm vụ không mang luồng: dò gói Nhà nước rồi Đảng như bảng chuyển trạng thái (suy luận). */
  static final List<String> FLOWS = List.of("NHA_NUOC", "DANG");

  private final PlanRepository plans;
  private final PeriodApi periods;
  private final OkrApi okr;
  private final OrgDirectoryApi org;
  private final PlanChecker checker;
  private final PolicyQueryApi policies;
  private final CatalogPolicy catalogPolicy;
  private final PlanLineFacts lineFacts;
  private final Clock clock;

  public PlanService(
      PlanRepository plans,
      PeriodApi periods,
      OkrApi okr,
      OrgDirectoryApi org,
      PlanChecker checker,
      PolicyQueryApi policies,
      CatalogPolicy catalogPolicy,
      Clock clock) {
    this(plans, periods, okr, org, checker, policies, catalogPolicy, PlanLineFacts.none(), clock);
  }

  /** {@code lineFacts}: tình trạng nhiệm vụ của dòng cho BR-04-11, BR-04-12 (CORE-02-10). */
  public PlanService(
      PlanRepository plans,
      PeriodApi periods,
      OkrApi okr,
      OrgDirectoryApi org,
      PlanChecker checker,
      PolicyQueryApi policies,
      CatalogPolicy catalogPolicy,
      PlanLineFacts lineFacts,
      Clock clock) {
    this.lineFacts = lineFacts == null ? PlanLineFacts.none() : lineFacts;
    this.plans = Objects.requireNonNull(plans, "plans");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.okr = Objects.requireNonNull(okr, "okr");
    this.org = org;
    this.checker = Objects.requireNonNull(checker, "checker");
    this.policies = policies;
    this.catalogPolicy = catalogPolicy == null ? CatalogPolicy.defaults() : catalogPolicy;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Nhóm điểm áp cho kế hoạch và gói Policy Pack đã cho ra nhóm, {@code null} khi dùng mặc định.
   */
  public record Groups(List<GroupWeight> weights, ResolvedPack pack, String flow) {}

  /** Kế hoạch kèm kỳ, phiên bản mới nhất, phiên bản hiệu lực và kỳ trước có kế hoạch đã duyệt. */
  /**
   * @param adjustmentsMax số lần điều chỉnh tối đa trong kỳ (BR-04-10, tham số Policy Pack {@link
   *     PlanAdjustments#MAX_KEY}); thiếu thì giao diện coi là 0 và khoá nút "Tạo điều chỉnh"
   */
  public record PlanView(
      PlanRow plan,
      PeriodSnapshot period,
      VersionRow currentVersion,
      Integer effectiveVersionNo,
      PeriodSnapshot previousPeriod,
      int adjustmentsUsed,
      int adjustmentsMax) {

    public PlanView(
        PlanRow plan,
        PeriodSnapshot period,
        VersionRow currentVersion,
        Integer effectiveVersionNo,
        PeriodSnapshot previousPeriod,
        int adjustmentsUsed) {
      this(
          plan,
          period,
          currentVersion,
          effectiveVersionNo,
          previousPeriod,
          adjustmentsUsed,
          PlanAdjustments.DEFAULT_MAX);
    }
  }

  /** Phiên bản kèm dòng. */
  /**
   * @param signRequestId yêu cầu ký SVC-10 đang gắn với phiên bản ({@code
   *     PlanVersion.signRequestId} của hợp đồng, WEB-065): giao diện đọc lại phiên bản sau 202 rồi
   *     theo dõi yêu cầu ký
   */
  public record VersionView(
      PlanRow plan,
      PeriodSnapshot period,
      VersionRow version,
      List<ItemRow> items,
      List<PlanChecker.Check> checks,
      Groups groups,
      UUID signRequestId) {

    public VersionView(
        PlanRow plan,
        PeriodSnapshot period,
        VersionRow version,
        List<ItemRow> items,
        List<PlanChecker.Check> checks,
        Groups groups) {
      this(plan, period, version, items, checks, groups, null);
    }
  }

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
    periods.assertWritable(tenant, PeriodId.of(plan(tenant, planId).periodId()));
    return saveVersion(tenant, planId, versionNo, expectedVersion, drafts, List.of(), actor);
  }

  /**
   * Lưu nháp; ở phiên bản điều chỉnh ghi kèm dòng bỏ và lý do (SCR-0403 trường 3). Bỏ việc đã có
   * minh chứng xác nhận (BR-04-11) hay đổi hạn nhiệm vụ đang thực hiện (BR-04-12) bị chặn ngay khi
   * lưu; thiếu lý do chỉ chặn khi gửi duyệt (BR-04-10).
   */
  @Transactional(TX)
  public VersionView saveVersion(
      TenantId tenant,
      UUID planId,
      int versionNo,
      long expectedVersion,
      List<ItemRow> drafts,
      List<RemovedItem> removed,
      UUID actor) {
    PlanRow plan = plan(tenant, planId);
    periods.assertWritable(tenant, PeriodId.of(plan.periodId()));
    VersionRow version = editable(tenant, plan, versionNo, expectedVersion, PlanCommand.SAVE);
    Map<UUID, CatalogRow> catalog = catalog(tenant, drafts);
    List<ItemRow> items = new ArrayList<>(drafts.size());
    for (ItemRow d : drafts) {
      items.add(storable(resolved(d, items.size() + 1, catalog)));
    }
    if (versionNo > 1) {
      List<RemovedItem> kept = removed == null ? List.of() : removed;
      List<Check> blocked =
          adjustmentChecks(tenant, plan, versionNo, items, kept).stream()
              .filter(PlanAdjustments::protectsResults)
              .toList();
      if (!blocked.isEmpty()) {
        throw new BusinessException(
            VALIDATION,
            blocked.get(0).message(),
            blocked.stream()
                .map(
                    c ->
                        new ProblemFieldError(
                            c.lineNo() == null ? "removedItems" : "items[" + (c.lineNo() - 1) + "]",
                            c.message(),
                            c.rule()))
                .toList());
      }
      plans.replaceRemovedItems(tenant, plan, version, kept);
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
    PeriodSnapshot period = period(tenant, plan.periodId());
    List<ItemRow> items = plans.items(tenant, plan.periodBucket(), v.id());
    Groups groups = groups(tenant, plan);
    List<Check> checks =
        new ArrayList<>(checker.check(items, context(tenant, plan, period, groups)));
    if (versionNo > 1) {
      checks.addAll(
          adjustmentChecks(
              tenant,
              plan,
              versionNo,
              items,
              plans.removedItems(tenant, plan.periodBucket(), v.id())));
    }
    UUID signRequestId =
        v.status() == PlanVersionStatus.CHO_DUYET || v.status() == PlanVersionStatus.DA_DUYET
            ? plans
                .approvalRow(tenant, plan.periodBucket(), v.id())
                .map(PlanRepository.ApprovalRow::signRequestId)
                .orElse(null)
            : null;
    return new VersionView(plan, period, v, items, List.copyOf(checks), groups, signRequestId);
  }

  /**
   * Kiểm tra tức thì các dòng chưa lưu (BR-04-02..07), không ghi CSDL; dòng thuộc danh mục lấy điểm
   * từ danh mục như khi lưu.
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<Check> check(TenantId tenant, UUID planId, int versionNo, List<ItemRow> drafts) {
    return check(tenant, planId, versionNo, drafts, List.of());
  }

  /** Như {@link #check(TenantId, UUID, int, List)}, kèm kiểm tra điều chỉnh BR-04-10..12. */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<Check> check(
      TenantId tenant,
      UUID planId,
      int versionNo,
      List<ItemRow> drafts,
      List<RemovedItem> removed) {
    PlanRow plan = plan(tenant, planId);
    plans.versions(tenant, plan.periodBucket(), planId).stream()
        .filter(x -> x.versionNo() == versionNo)
        .findFirst()
        .orElseThrow(NoSuchElementException::new);
    Map<UUID, CatalogRow> catalog = catalog(tenant, drafts);
    List<ItemRow> items = new ArrayList<>(drafts.size());
    for (ItemRow d : drafts) {
      items.add(resolved(d, items.size() + 1, catalog));
    }
    PeriodSnapshot period = period(tenant, plan.periodId());
    List<Check> checks =
        new ArrayList<>(checker.check(items, context(tenant, plan, period, groups(tenant, plan))));
    if (versionNo > 1) {
      checks.addAll(
          adjustmentChecks(tenant, plan, versionNo, items, removed == null ? List.of() : removed));
    }
    return List.copyOf(checks);
  }

  /**
   * Phiên bản gốc của phiên bản điều chỉnh {@code versionNo}: phiên bản {@code DA_DUYET} có số lớn
   * nhất nhỏ hơn nó.
   */
  Optional<VersionRow> baseOf(TenantId tenant, PlanRow plan, int versionNo) {
    return plans.versions(tenant, plan.periodBucket(), plan.id()).stream()
        .filter(x -> x.versionNo() < versionNo && x.status() == PlanVersionStatus.DA_DUYET)
        .findFirst();
  }

  private List<Check> adjustmentChecks(
      TenantId tenant,
      PlanRow plan,
      int versionNo,
      List<ItemRow> items,
      List<RemovedItem> removed) {
    Optional<VersionRow> base = baseOf(tenant, plan, versionNo);
    if (base.isEmpty()) {
      return List.of();
    }
    List<ItemRow> baseItems = plans.items(tenant, plan.periodBucket(), base.get().id());
    PlanAdjustments.Diff diff =
        PlanAdjustments.diff(base.get().versionNo(), versionNo, baseItems, items, removed);
    return PlanAdjustments.checks(
        diff,
        lineFacts.of(tenant, plan.periodBucket(), baseItems.stream().map(ItemRow::id).toList()));
  }

  /** Tham số {@code key} của Policy Pack mà kỳ của kế hoạch đã ghim. */
  Optional<Object> parameter(TenantId tenant, PlanRow plan, String key) {
    if (policies == null) {
      return Optional.empty();
    }
    for (String flow : FLOWS) {
      Optional<ResolvedPack> pack =
          policies.resolve(tenant, PeriodId.of(plan.periodId()), flow, null, null);
      if (pack.isPresent()) {
        return policies.parameter(tenant, pack.get().versionId(), key);
      }
    }
    return Optional.empty();
  }

  /** Nhóm điểm từ tham số {@code nhom} của Policy Pack mà kỳ đã ghim, mặc định 30/70. */
  Groups groups(TenantId tenant, PlanRow plan) {
    if (policies != null) {
      for (String flow : FLOWS) {
        Optional<ResolvedPack> pack =
            policies.resolve(tenant, PeriodId.of(plan.periodId()), flow, null, null);
        if (pack.isPresent()) {
          List<GroupWeight> weights =
              policies
                  .parameter(tenant, pack.get().versionId(), "nhom")
                  .map(PlanService::weights)
                  .orElse(PlanChecker.DEFAULT_GROUPS);
          return new Groups(weights, pack.get(), flow);
        }
      }
    }
    return new Groups(PlanChecker.DEFAULT_GROUPS, null, null);
  }

  private PlanChecker.Context context(
      TenantId tenant, PlanRow plan, PeriodSnapshot period, Groups groups) {
    List<PlanChecker.KeyResultRef> krs =
        okr
            .listApprovedKeyResults(tenant, PeriodId.of(plan.periodId()), plan.ownerUnitId())
            .stream()
            .map(k -> new PlanChecker.KeyResultRef(k.id(), k.title()))
            .toList();
    return new PlanChecker.Context(
        period.startDate(),
        period.endDate(),
        catalogPolicy.vagueWords(tenant),
        groups.weights(),
        krs);
  }

  /** {@code params.nhom}: danh sách {@code {ma, diem_toi_da, nhom_nhiem_vu}}. */
  static List<GroupWeight> weights(Object value) {
    if (!(value instanceof Collection<?> rows) || rows.isEmpty()) {
      return PlanChecker.DEFAULT_GROUPS;
    }
    List<GroupWeight> out = new ArrayList<>();
    for (Object row : rows) {
      if (!(row instanceof Map<?, ?> m) || m.get("ma") == null || m.get("diem_toi_da") == null) {
        return PlanChecker.DEFAULT_GROUPS;
      }
      Set<String> taskGroups = new java.util.LinkedHashSet<>();
      if (m.get("nhom_nhiem_vu") instanceof Collection<?> gs) {
        gs.forEach(g -> taskGroups.add(String.valueOf(g)));
      } else {
        taskGroups.add(String.valueOf(m.get("ma")));
      }
      out.add(
          new GroupWeight(
              String.valueOf(m.get("ma")),
              new BigDecimal(String.valueOf(m.get("diem_toi_da"))),
              Set.copyOf(taskGroups)));
    }
    return out;
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
    int max =
        PlanAdjustments.allowed(
            PlanAdjustmentService.integer(parameter(tenant, plan, PlanAdjustments.MAX_KEY))
                .orElse(PlanAdjustments.DEFAULT_MAX),
            0);
    return new PlanView(plan, period, versions.get(0), effective, previous, used, max);
  }

  private VersionRow editable(
      TenantId tenant, PlanRow plan, int versionNo, long expected, PlanCommand command) {
    VersionRow version =
        plans
            .lockVersion(tenant, plan.periodBucket(), plan.id(), versionNo)
            .orElseThrow(NoSuchElementException::new);
    if (version.rowVersion() != expected) {
      throw new VersionConflictException("TASK", expected, version.rowVersion());
    }
    PlanTransition result = PlanVersionStateMachine.apply(version.status(), command, true);
    if (result instanceof PlanTransition.Rejected r) {
      throw new BusinessException(INVALID_TRANSITION, r.message());
    }
    return version;
  }

  private Map<UUID, CatalogRow> catalog(TenantId tenant, List<ItemRow> drafts) {
    return plans.catalogItems(
        tenant, drafts.stream().map(ItemRow::catalogItemId).filter(Objects::nonNull).toList());
  }

  /**
   * Dòng có số thứ tự; dòng thuộc danh mục lấy điểm, mức khó, đơn vị tính, sản phẩm từ danh mục,
   * dòng ngoài danh mục để trống điểm cho bước duyệt (BR-04-05).
   */
  private static ItemRow resolved(ItemRow d, int itemNo, Map<UUID, CatalogRow> catalog) {
    CatalogRow c = d.catalogItemId() == null ? null : catalog.get(d.catalogItemId());
    if (d.catalogItemId() != null && c == null) {
      throw invalid(
          "items[" + (itemNo - 1) + "].catalogItemId", "Công việc không có trong danh mục áp dụng");
    }
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
        c == null ? d.unit() : c.unit(),
        c == null ? null : c.difficulty(),
        c == null ? null : c.standardScore(),
        d.dueDate(),
        d.originItemId(),
        d.changeReason(),
        d.recurrence());
  }

  /**
   * Bản nháp lưu được dù còn lỗi chặn của BR-04-02..07; chỉ từ chối giá trị mà bảng {@code
   * task.plan_item} không giữ được (thiếu chủ trì, nội dung, hạn, đơn vị tính, khối lượng ≤ 0).
   */
  private static ItemRow storable(ItemRow i) {
    String field = "items[" + (i.itemNo() - 1) + "]";
    if (i.lead() == null || i.lead().id() == null) {
      throw invalid(field + ".leadAssignee", "Chọn một chủ trì duy nhất");
    }
    if (i.title() == null || i.title().isBlank()) {
      throw invalid(field + ".title", "Nhập nội dung công việc");
    }
    if (i.quantity() == null || i.quantity().signum() <= 0) {
      throw invalid(field + ".quantity", "Khối lượng phải lớn hơn 0");
    }
    if (i.unit() == null || i.unit().isBlank()) {
      throw invalid(field + ".unit", "Nhập đơn vị tính");
    }
    if (i.dueDate() == null) {
      throw invalid(field + ".dueDate", "Nhập hạn hoàn thành");
    }
    return i;
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
