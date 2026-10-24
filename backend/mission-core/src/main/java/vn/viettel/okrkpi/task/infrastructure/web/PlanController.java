package vn.viettel.okrkpi.task.infrastructure.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.contract.tasks.model.AssigneeRef;
import vn.viettel.okrkpi.contract.tasks.model.CheckPlanVersion200Response;
import vn.viettel.okrkpi.contract.tasks.model.CopyPlanFromPreviousPeriodRequest;
import vn.viettel.okrkpi.contract.tasks.model.CreatePlanAdjustmentRequest;
import vn.viettel.okrkpi.contract.tasks.model.CreatePlanRequest;
import vn.viettel.okrkpi.contract.tasks.model.Flow;
import vn.viettel.okrkpi.contract.tasks.model.ListApprovedPlanItems200Response;
import vn.viettel.okrkpi.contract.tasks.model.ListPlanApprovals200Response;
import vn.viettel.okrkpi.contract.tasks.model.ListPlanVersions200Response;
import vn.viettel.okrkpi.contract.tasks.model.ListPlans200Response;
import vn.viettel.okrkpi.contract.tasks.model.PeriodRef;
import vn.viettel.okrkpi.contract.tasks.model.Plan;
import vn.viettel.okrkpi.contract.tasks.model.PlanCheck;
import vn.viettel.okrkpi.contract.tasks.model.PlanDiff;
import vn.viettel.okrkpi.contract.tasks.model.PlanDiffLine;
import vn.viettel.okrkpi.contract.tasks.model.PlanItem;
import vn.viettel.okrkpi.contract.tasks.model.PlanItemInput;
import vn.viettel.okrkpi.contract.tasks.model.PlanItemRef;
import vn.viettel.okrkpi.contract.tasks.model.PlanItemScoreRequest;
import vn.viettel.okrkpi.contract.tasks.model.PlanRemovedItem;
import vn.viettel.okrkpi.contract.tasks.model.PlanVersion;
import vn.viettel.okrkpi.contract.tasks.model.PlanVersionGroupWeights;
import vn.viettel.okrkpi.contract.tasks.model.PlanVersionInput;
import vn.viettel.okrkpi.contract.tasks.model.PlanVersionStatus;
import vn.viettel.okrkpi.contract.tasks.model.PlanVersionSummary;
import vn.viettel.okrkpi.contract.tasks.model.ReturnPlanVersionRequest;
import vn.viettel.okrkpi.contract.tasks.model.SuggestPlanItemsFromOkr200Response;
import vn.viettel.okrkpi.contract.tasks.model.TaskGroup;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.plan.PlanAdjustmentService;
import vn.viettel.okrkpi.task.application.plan.PlanAdjustments;
import vn.viettel.okrkpi.task.application.plan.PlanChecker;
import vn.viettel.okrkpi.task.application.plan.PlanDeadlines;
import vn.viettel.okrkpi.task.application.plan.PlanReviewService;
import vn.viettel.okrkpi.task.application.plan.PlanService;
import vn.viettel.okrkpi.task.application.plan.PlanService.ApprovedItem;
import vn.viettel.okrkpi.task.application.plan.PlanService.PlanView;
import vn.viettel.okrkpi.task.application.plan.PlanService.VersionView;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.Assignee;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.RemovedItem;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.VersionRow;

/**
 * Tám thao tác kế hoạch quý của {@code tasks.yaml} (QT-04, SCR-0402, CORE-02-05). Tên người, tên
 * đơn vị để trống cho tới khi có danh bạ tên ở {@code replica :: api}. Kết quả kiểm tra
 * BR-04-02..07 và trọng số nhóm theo Policy Pack do {@link
 * vn.viettel.okrkpi.task.application.plan.PlanChecker} tính (CORE-02-06).
 */
@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

  private final PlanService plans;
  private final CursorCodec cursors;
  private final TaskCallers callers;
  private final PlanDeadlines deadlines;
  private final PlanReviewService reviews;
  private final PlanAdjustmentService adjustments;

  public PlanController(
      PlanService plans,
      CursorCodec cursors,
      TaskCallers callers,
      PlanDeadlines deadlines,
      PlanReviewService reviews,
      PlanAdjustmentService adjustments) {
    this.adjustments = Objects.requireNonNull(adjustments, "adjustments");
    this.plans = Objects.requireNonNull(plans, "plans");
    this.reviews = Objects.requireNonNull(reviews, "reviews");
    this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @GetMapping
  @RequiresPermission(operationId = "listPlans", module = "plans", resourceType = "plan")
  public ListPlans200Response listPlans(
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "unit", required = false) UUID unit,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    PageRequest page =
        cursors.pageRequest(limit, cursor, "plans:" + period + ":" + unit + ":" + status);
    UUID after = page.afterKey().map(k -> k.getUuid(0)).orElse(null);
    CursorPage<PlanView> result =
        cursors.page(
            found(
                () ->
                    plans.listPlans(tenant, period, unit, status(status), after, page.fetchSize())),
            page,
            v -> CursorKey.of(v.plan().id()));
    return new ListPlans200Response(
        result.items().stream().map(this::planDto).toList(), result.nextCursor());
  }

  @PostMapping
  @Idempotent
  @RequiresPermission(operationId = "createPlan", module = "plans", resourceType = "plan")
  public ResponseEntity<Plan> createPlan(@RequestBody CreatePlanRequest body) {
    if (body == null || body.getPeriodId() == null) {
      throw invalid("periodId");
    }
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current().personId();
    PlanView view =
        found(() -> plans.createPlan(tenant, body.getPeriodId(), body.getUnitId(), actor));
    return ResponseEntity.created(URI.create("/api/v1/plans/" + view.plan().id()))
        .body(planDto(view));
  }

  /** SCR-0403 "Chờ tôi duyệt" (CORE-02-08). */
  @GetMapping("/approvals")
  @RequiresPermission(operationId = "listPlanApprovals", module = "plans", resourceType = "plan")
  public ListPlanApprovals200Response listPlanApprovals(
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current().personId();
    PageRequest page = cursors.pageRequest(limit, cursor, "plan-approvals:" + actor);
    UUID after = page.afterKey().map(k -> k.getUuid(0)).orElse(null);
    CursorPage<PlanReviewService.Review> result =
        cursors.page(
            reviews.approvals(tenant, actor, after, page.fetchSize()),
            page,
            r -> CursorKey.of(r.row().plan().id()));
    return new ListPlanApprovals200Response(
        result.items().stream().map(r -> summary(r.row().version(), r.period())).toList(),
        result.nextCursor());
  }

  @GetMapping("/items")
  @RequiresPermission(
      operationId = "listApprovedPlanItems",
      module = "plans",
      resourceType = "plan_item")
  public ListApprovedPlanItems200Response listApprovedPlanItems(
      @RequestParam(value = "period", required = false) UUID period,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    TenantId tenant = TenantContext.require();
    PageRequest page = cursors.pageRequest(limit, cursor, "plan-items:" + period + ":" + status);
    UUID afterPlan = page.afterKey().map(k -> k.getUuid(0)).orElse(null);
    int afterNo = page.afterKey().map(k -> (int) k.getLong(1)).orElse(0);
    CursorPage<ApprovedItem> result =
        cursors.page(
            found(
                () ->
                    plans.approvedItems(
                        tenant, period, status(status), afterPlan, afterNo, page.fetchSize())),
            page,
            a -> CursorKey.of(a.row().planId(), a.row().item().itemNo()));
    return new ListApprovedPlanItems200Response(
        result.items().stream().map(PlanController::itemRef).toList(), result.nextCursor());
  }

  @GetMapping("/{id}/versions")
  @RequiresPermission(operationId = "listPlanVersions", module = "plans", resourceType = "plan")
  public ListPlanVersions200Response listPlanVersions(@PathVariable("id") UUID id) {
    PlanService.Versions versions = found(() -> plans.versions(TenantContext.require(), id));
    return new ListPlanVersions200Response(
        versions.rows().stream().map(v -> summary(v, versions.period())).toList());
  }

  /** Điều chỉnh kế hoạch: phiên bản n+1 {@code NHAP} (BR-04-10, CORE-02-10). */
  @PostMapping("/{id}/versions")
  @Idempotent
  @RequiresPermission(operationId = "createPlanAdjustment", module = "plans", resourceType = "plan")
  public ResponseEntity<PlanVersion> createPlanAdjustment(
      @PathVariable("id") UUID id, @RequestBody CreatePlanAdjustmentRequest body) {
    String reason = body == null ? null : body.getChangeReason();
    UUID actor = callers.current().personId();
    VersionView view = found(() -> adjustments.create(TenantContext.require(), id, reason, actor));
    return ResponseEntity.created(
            URI.create("/api/v1/plans/" + id + "/versions/" + view.version().versionNo()))
        .eTag(ETags.of(view.version().rowVersion()))
        .body(versionDto(view));
  }

  /** SCR-0403: so sánh thêm, sửa, bỏ giữa hai phiên bản theo {@code origin_item_id}. */
  @GetMapping("/{id}/versions/{v}/diff")
  @RequiresPermission(operationId = "diffPlanVersions", module = "plans", resourceType = "plan")
  public PlanDiff diffPlanVersions(
      @PathVariable("id") UUID id,
      @PathVariable("v") int v,
      @RequestParam("base") int base,
      @RequestParam(value = "mode", required = false, defaultValue = "changes") String mode) {
    if (base < 0) {
      throw invalid("base");
    }
    if (!"changes".equals(mode) && !"all".equals(mode)) {
      throw invalid("mode");
    }
    PlanAdjustmentService.DiffView view =
        found(() -> adjustments.diff(TenantContext.require(), id, v, base));
    PlanAdjustments.Diff d = view.diff();
    PlanDiff dto =
        new PlanDiff(
            d.baseVersionNo(),
            d.versionNo(),
            d.count(PlanAdjustments.Change.THEM),
            d.count(PlanAdjustments.Change.SUA),
            d.count(PlanAdjustments.Change.BO),
            d.lines().stream()
                .filter(l -> "all".equals(mode) || l.change() != PlanAdjustments.Change.KHONG_DOI)
                .map(PlanController::diffLine)
                .toList());
    dto.setGroupWeightChanged(view.groupWeightChanged());
    dto.setAdjustmentNo(view.adjustmentNo());
    dto.setAdjustmentsMax(view.adjustmentsMax());
    return dto;
  }

  private static PlanDiffLine diffLine(PlanAdjustments.Line l) {
    PlanDiffLine dto = new PlanDiffLine(PlanDiffLine.ChangeEnum.fromValue(l.change().name()));
    if (l.before() != null) {
      dto.setBefore(item(l.before()));
    }
    if (l.after() != null) {
      dto.setAfter(item(l.after()));
    }
    dto.setChangedFields(l.changedFields());
    if (l.reason() != null && !l.reason().isBlank()) {
      dto.setReason(l.reason());
    }
    return dto;
  }

  @GetMapping("/{id}/versions/{v}")
  @RequiresPermission(operationId = "getPlanVersion", module = "plans", resourceType = "plan")
  public ResponseEntity<PlanVersion> getPlanVersion(
      @PathVariable("id") UUID id, @PathVariable("v") int v) {
    VersionView view = found(() -> plans.version(TenantContext.require(), id, v));
    return ETags.ok(versionDto(view), view.version().rowVersion());
  }

  @PutMapping("/{id}/versions/{v}")
  @Idempotent
  @RequiresPermission(operationId = "savePlanVersion", module = "plans", resourceType = "plan")
  public ResponseEntity<PlanVersion> savePlanVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") int v,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody PlanVersionInput body) {
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    if (body == null || body.getItems() == null) {
      throw invalid("items");
    }
    List<ItemRow> drafts = body.getItems().stream().map(PlanController::draft).toList();
    List<RemovedItem> removed = removed(body);
    UUID actor = callers.current().personId();
    VersionView view =
        found(
            () ->
                plans.saveVersion(
                    TenantContext.require(), id, v, expected, drafts, removed, actor));
    return ETags.ok(versionDto(view), view.version().rowVersion());
  }

  @PostMapping("/{id}/versions/{v}/copy-from")
  @Idempotent
  @RequiresPermission(
      operationId = "copyPlanFromPreviousPeriod",
      module = "plans",
      resourceType = "plan")
  public ResponseEntity<PlanVersion> copyPlanFromPreviousPeriod(
      @PathVariable("id") UUID id,
      @PathVariable("v") int v,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) CopyPlanFromPreviousPeriodRequest body) {
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    UUID source = body == null ? null : body.getSourcePeriodId();
    UUID actor = callers.current().personId();
    VersionView view =
        found(
            () ->
                plans.copyFromPreviousPeriod(
                    TenantContext.require(), id, v, expected, source, actor));
    return ETags.ok(versionDto(view), view.version().rowVersion());
  }

  @PostMapping("/{id}/versions/{v}/submit")
  @Idempotent
  @RequiresPermission(operationId = "submitPlanVersion", module = "plans", resourceType = "plan")
  public ResponseEntity<PlanVersion> submitPlanVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") int v,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    UUID actor = callers.current().personId();
    VersionView view =
        found(() -> reviews.submitVersion(TenantContext.require(), id, v, expected, actor));
    return ETags.ok(versionDto(view), view.version().rowVersion());
  }

  @PostMapping("/{id}/versions/{v}/return")
  @Idempotent
  @RequiresPermission(operationId = "returnPlanVersion", module = "plans", resourceType = "plan")
  public ResponseEntity<PlanVersion> returnPlanVersion(
      @PathVariable("id") UUID id,
      @PathVariable("v") int v,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody ReturnPlanVersionRequest body) {
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    String comment = body == null ? null : body.getComment();
    UUID actor = callers.current().personId();
    VersionView view =
        found(
            () -> reviews.returnVersion(TenantContext.require(), id, v, expected, comment, actor));
    return ETags.ok(versionDto(view), view.version().rowVersion());
  }

  @PostMapping("/{id}/versions/{v}/items/{itemId}/score")
  @Idempotent
  @RequiresPermission(
      operationId = "scoreOffCatalogPlanItem",
      module = "plans",
      resourceType = "plan")
  public ResponseEntity<PlanVersion> scoreOffCatalogPlanItem(
      @PathVariable("id") UUID id,
      @PathVariable("v") int v,
      @PathVariable("itemId") UUID itemId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody PlanItemScoreRequest body) {
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    if (body == null) {
      throw invalid("difficulty");
    }
    UUID actor = callers.current().personId();
    VersionView view =
        found(
            () ->
                reviews.scoreItem(
                    TenantContext.require(),
                    id,
                    v,
                    itemId,
                    expected,
                    body.getDifficulty(),
                    body.getStandardScore(),
                    actor));
    return ETags.ok(versionDto(view), view.version().rowVersion());
  }

  @PostMapping("/{id}/versions/{v}/check")
  @Idempotent
  @RequiresPermission(operationId = "checkPlanVersion", module = "plans", resourceType = "plan")
  public CheckPlanVersion200Response checkPlanVersion(
      @PathVariable("id") UUID id, @PathVariable("v") int v, @RequestBody PlanVersionInput body) {
    if (body == null || body.getItems() == null) {
      throw invalid("items");
    }
    List<ItemRow> drafts = body.getItems().stream().map(PlanController::draft).toList();
    List<RemovedItem> removed = removed(body);
    return new CheckPlanVersion200Response(
        found(() -> plans.check(TenantContext.require(), id, v, drafts, removed)).stream()
            .map(PlanController::check)
            .toList());
  }

  @GetMapping("/{id}/suggestions")
  @RequiresPermission(
      operationId = "suggestPlanItemsFromOkr",
      module = "plans",
      resourceType = "plan")
  public SuggestPlanItemsFromOkr200Response suggestPlanItemsFromOkr(@PathVariable("id") UUID id) {
    return new SuggestPlanItemsFromOkr200Response(
        found(() -> plans.suggestions(TenantContext.require(), id)).stream()
            .map(PlanController::input)
            .toList());
  }

  /** Kế hoạch, phiên bản hay kỳ không có trong tenant: 404. */
  private static <T> T found(Supplier<T> work) {
    try {
      return work.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  /** Hạn duyệt BR-04-01 tính khi đọc (CORE-02-07). */
  private Plan planDto(PlanView v) {
    Plan dto = plan(v);
    dto.setApprovalDeadline(deadlines.deadlineOf(TenantContext.require(), v.period()));
    return dto;
  }

  private PlanVersion versionDto(VersionView v) {
    PlanVersion dto = version(v);
    LocalDate deadline = deadlines.deadlineOf(TenantContext.require(), v.period());
    dto.setApprovalDeadline(deadline);
    if (v.version().versionNo() == 1 && !"DA_DUYET".equals(v.version().status().name())) {
      dto.setWorkingDaysToDeadline(deadlines.workingDaysTo(TenantContext.require(), deadline));
    }
    return dto;
  }

  static Plan plan(PlanView v) {
    Plan dto =
        new Plan(
            v.plan().id(),
            "KH-" + v.period().code(),
            period(v.period()),
            v.plan().ownerUnitId(),
            "",
            summary(v.currentVersion(), v.period()),
            (int) v.plan().rowVersion());
    dto.setEffectiveVersionNo(v.effectiveVersionNo());
    dto.setPreviousPeriod(v.previousPeriod() == null ? null : period(v.previousPeriod()));
    dto.setAdjustmentsUsed(v.adjustmentsUsed());
    dto.setAdjustmentsMax(v.adjustmentsMax());
    return dto;
  }

  static PlanVersionSummary summary(VersionRow r, PeriodSnapshot period) {
    PlanVersionSummary dto =
        new PlanVersionSummary(
            r.planId(),
            r.versionNo(),
            PlanVersionStatus.fromValue(r.status().name()),
            "",
            period.name(),
            r.itemCount());
    if (r.submittedAt() != null) {
      dto.setSubmittedAt(OffsetDateTime.ofInstant(r.submittedAt(), ZoneOffset.UTC));
    }
    if (r.approvedAt() != null) {
      dto.setApprovedAt(OffsetDateTime.ofInstant(r.approvedAt(), ZoneOffset.UTC));
    }
    return dto;
  }

  static PlanVersion version(VersionView v) {
    VersionRow r = v.version();
    PlanVersion dto =
        new PlanVersion(
            r.planId(),
            r.versionNo(),
            PlanVersionStatus.fromValue(r.status().name()),
            period(v.period()),
            "",
            v.items().stream().map(PlanController::item).toList(),
            weights(v.groups()),
            v.checks().stream().map(PlanController::check).toList(),
            (int) r.rowVersion());
    dto.setContentHash(r.contentHash() == null ? null : HexFormat.of().formatHex(r.contentHash()));
    dto.setReviewComment(r.reviewComment());
    dto.setChangeReason(r.changeReason());
    dto.setSignRequestId(v.signRequestId());
    return dto;
  }

  static PlanCheck check(PlanChecker.Check c) {
    PlanCheck dto =
        new PlanCheck(c.rule(), PlanCheck.LevelEnum.fromValue(c.level().name()), c.message());
    if (c.lineNo() != null) {
      dto.setLineNo(c.lineNo());
    }
    if (c.msg() != null) {
      dto.setMsg(c.msg());
    }
    return dto;
  }

  /** Điểm tối đa của nhóm chứa từng nhóm nhiệm vụ, kèm gói Policy Pack đã cho ra nhóm. */
  static PlanVersionGroupWeights weights(PlanService.Groups g) {
    PlanVersionGroupWeights dto =
        new PlanVersionGroupWeights(max(g, "THUONG_XUYEN"), max(g, "THEN_CHOT"));
    if (g.pack() != null) {
      dto.setPolicyPackCode(g.pack().code());
      dto.setPolicyPackVersion(g.pack().version());
      dto.setFlow(Flow.fromValue(g.flow()));
    }
    return dto;
  }

  private static BigDecimal max(PlanService.Groups g, String taskGroup) {
    return g.weights().stream()
        .filter(w -> w.taskGroups().contains(taskGroup))
        .map(PlanChecker.GroupWeight::maxPoints)
        .findFirst()
        .orElse(BigDecimal.ZERO);
  }

  static PlanItem item(ItemRow i) {
    PlanItem dto =
        new PlanItem(
            i.title(),
            TaskGroup.fromValue(i.taskGroup()),
            ref(i.lead()),
            i.productName(),
            i.quantity(),
            i.dueDate(),
            i.itemNo());
    dto.setId(i.id());
    dto.setOriginItemId(i.originItemId());
    dto.setKeyResultId(i.keyResultId());
    dto.setCoAssignees(i.coAssignees().stream().map(PlanController::ref).toList());
    dto.setCatalogItemId(i.catalogItemId());
    dto.setCatalogItemCode(i.catalogCode());
    dto.setUnit(i.unit());
    dto.setStandardScore(i.standardScore());
    dto.setPendingScore(i.catalogItemId() == null && i.standardScore() == null);
    if (i.recurrence() != null) {
      dto.setRecurrence(PlanItem.RecurrenceEnum.fromValue(i.recurrence()));
    }
    if (i.changeReason() != null) {
      dto.setChangeReason(i.changeReason());
    }
    return dto;
  }

  static PlanItemInput input(ItemRow i) {
    PlanItemInput dto =
        new PlanItemInput(
            i.title(),
            TaskGroup.fromValue(i.taskGroup()),
            ref(i.lead()),
            i.productName(),
            i.quantity(),
            i.dueDate());
    dto.setKeyResultId(i.keyResultId());
    dto.setUnit(i.unit());
    return dto;
  }

  static PlanItemRef itemRef(ApprovedItem a) {
    ItemRow i = a.row().item();
    PlanItemRef dto =
        new PlanItemRef(
            i.id(),
            "KH-" + a.period().code(),
            a.row().versionNo(),
            i.itemNo(),
            i.title(),
            TaskGroup.fromValue(i.taskGroup()),
            i.productName(),
            i.quantity(),
            i.unit(),
            i.dueDate(),
            a.row().evidenceRequirement() == null ? "" : a.row().evidenceRequirement(),
            a.row().qualityStandard() == null ? "" : a.row().qualityStandard());
    dto.setCatalogItemId(i.catalogItemId());
    dto.setKeyResultId(i.keyResultId());
    return dto;
  }

  private static List<RemovedItem> removed(PlanVersionInput body) {
    return body.getRemovedItems() == null
        ? List.of()
        : body.getRemovedItems().stream()
            .map((PlanRemovedItem r) -> new RemovedItem(r.getOriginItemId(), r.getChangeReason()))
            .toList();
  }

  private static ItemRow draft(PlanItemInput in) {
    return new ItemRow(
        in.getId(),
        0,
        in.getCatalogItemId(),
        null,
        in.getKeyResultId(),
        in.getTitle(),
        in.getTaskGroup() == null ? null : in.getTaskGroup().getValue(),
        assignee(in.getLeadAssignee()),
        in.getCoAssignees() == null
            ? List.of()
            : in.getCoAssignees().stream().map(PlanController::assignee).toList(),
        in.getProductName(),
        in.getQuantity(),
        in.getUnit(),
        null,
        in.getStandardScore(),
        in.getDueDate(),
        in.getOriginItemId(),
        in.getChangeReason(),
        in.getRecurrence() == null ? null : in.getRecurrence().getValue());
  }

  private static Assignee assignee(AssigneeRef r) {
    return r == null
        ? null
        : new Assignee(r.getKind() == null ? null : r.getKind().getValue(), r.getId(), r.getName());
  }

  private static AssigneeRef ref(Assignee a) {
    return new AssigneeRef(
        AssigneeRef.KindEnum.fromValue(a.kind()), a.id(), a.name() == null ? "" : a.name());
  }

  private static PeriodRef period(PeriodSnapshot p) {
    return new PeriodRef(p.id().value(), p.name(), p.startDate(), p.endDate());
  }

  private static vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus status(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw invalid("status");
    }
  }

  private static BusinessException invalid(String field) {
    String message = "Giá trị không hợp lệ";
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
