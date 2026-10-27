package vn.viettel.okrkpi.config.infrastructure.web;

import java.net.URI;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.application.NewPeriod;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.application.PeriodLockService;
import vn.viettel.okrkpi.config.application.PeriodView;
import vn.viettel.okrkpi.config.application.port.PeriodRepository;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.contract.config.model.Period;
import vn.viettel.okrkpi.contract.config.model.PeriodCreate;
import vn.viettel.okrkpi.contract.config.model.PeriodFlow;
import vn.viettel.okrkpi.contract.config.model.PeriodKind;
import vn.viettel.okrkpi.contract.config.model.PeriodMilestones;
import vn.viettel.okrkpi.contract.config.model.PeriodPage;
import vn.viettel.okrkpi.contract.config.model.PeriodStatus;
import vn.viettel.okrkpi.contract.config.model.Ref;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@code GET/POST /api/v1/periods} ({@code listPeriods}, {@code createPeriod}) và {@code GET
 * /api/v1/periods/{id}} ({@code getPeriod}) của {@code config.yaml} (QT-08, SCR-0801). Quyền theo
 * ma trận qua OPA: đọc kỳ theo dòng API {@code periods} (mọi vai trò nghiệp vụ), tạo kỳ theo {@code
 * SCR-0801.tao-ky}.
 */
@RestController
@RequestMapping("/api/v1/periods")
public class PeriodController {

  private final PeriodConfigService periods;
  private final CursorCodec cursors;
  private final Callers callers;
  private final ConfigIdempotency idempotency;
  private final ObjectProvider<PeriodLockService> locks;

  public PeriodController(
      PeriodConfigService periods,
      CursorCodec cursors,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodCaller> callers,
      ConfigIdempotency idempotency,
      ObjectProvider<PeriodLockService> locks) {
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.locks = Objects.requireNonNull(locks, "locks");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.callers = new Callers(subjects, callers);
  }

  @PostMapping
  @RequiresPermission(
      action = "SCR-0801.tao-ky",
      operationId = "createPeriod",
      module = "config",
      resourceType = "period")
  public ResponseEntity<Period> createPeriod(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody PeriodCreate body) {
    TenantId tenant = TenantContext.require();
    UUID actor = caller();
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        "/api/v1/periods",
        body,
        Period.class,
        Period::getRowVersion,
        () -> create(tenant, actor, body));
  }

  private ResponseEntity<Period> create(TenantId tenant, UUID actor, PeriodCreate body) {
    PeriodMilestones m = body.getMilestones();
    PeriodView created =
        periods.create(
            tenant,
            actor,
            new NewPeriod(
                body.getCode(),
                body.getName(),
                body.getKind() == null
                    ? null
                    : vn.viettel.okrkpi.config.domain.PeriodKind.valueOf(body.getKind().getValue()),
                body.getFlow() == null
                    ? null
                    : vn.viettel.okrkpi.config.domain.PeriodFlow.valueOf(body.getFlow().getValue()),
                body.getParentPeriodId() == null ? null : PeriodId.of(body.getParentPeriodId()),
                body.getStartDate(),
                body.getEndDate(),
                body.getPlanDeadline(),
                body.getSubmissionDeadline(),
                m == null
                    ? null
                    : new vn.viettel.okrkpi.config.domain.PeriodMilestones(
                        m.getPlanApprovalDeadline(),
                        m.getEvidenceDeadline(),
                        m.getAcceptanceBacklogDeadline(),
                        m.getPlannedLockDate())));
    Period dto = toDto(created);
    return ResponseEntity.created(URI.create("/api/v1/periods/" + dto.getId()))
        .eTag(ETags.of(dto.getRowVersion()))
        .body(dto);
  }

  @GetMapping("/{id}")
  // Đọc kỳ là quyền chung của mọi vai trò theo dòng API `periods` của ma trận (bộ chọn kỳ ở trang
  // chủ, kế hoạch,
  // giao việc), không phải chức năng SCR-0801.xem của màn hình quản trị kỳ, nên chỉ xét theo API.
  @RequiresPermission(operationId = "getPeriod", module = "periods", resourceType = "period")
  public ResponseEntity<Period> getPeriod(@PathVariable("id") UUID id) {
    Period dto =
        periods
            .get(TenantContext.require(), PeriodId.of(id))
            .map(PeriodController::toDto)
            .map(this::withLockOrder)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return ETags.ok(dto, dto.getRowVersion());
  }

  @GetMapping
  // Đọc kỳ là quyền chung của mọi vai trò theo dòng API `periods` của ma trận (bộ chọn kỳ ở trang
  // chủ, kế hoạch,
  // giao việc), không phải chức năng SCR-0801.xem của màn hình quản trị kỳ, nên chỉ xét theo API.
  @RequiresPermission(operationId = "listPeriods", module = "periods", resourceType = "period")
  public PeriodPage listPeriods(
      @RequestParam(value = "year", required = false) Integer year,
      @RequestParam(value = "kind", required = false) PeriodKind kind,
      @RequestParam(value = "flow", required = false) PeriodFlow flow,
      @RequestParam(value = "status", required = false) PeriodStatus status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    if (year != null && (year < 2020 || year > 2100)) {
      throw new BusinessException("COMMON-422-VALIDATION", "Năm phải trong khoảng 2020 đến 2100.");
    }
    String scope = "periods:" + year + ":" + kind + ":" + flow + ":" + status;
    PageRequest page = cursors.pageRequest(limit, cursor, scope);
    PeriodRepository.Keyset after =
        page.afterKey()
            .map(k -> new PeriodRepository.Keyset(k.getDate(0), k.getUuid(1)))
            .orElse(null);
    List<PeriodView> fetched =
        periods.list(
            TenantContext.require(),
            new PeriodRepository.Filter(
                year,
                kind == null
                    ? null
                    : vn.viettel.okrkpi.config.domain.PeriodKind.valueOf(kind.getValue()),
                flow == null
                    ? null
                    : vn.viettel.okrkpi.config.domain.PeriodFlow.valueOf(flow.getValue()),
                status == null ? null : PeriodState.valueOf(status.getValue())),
            after,
            page.fetchSize());
    CursorPage<PeriodView> result =
        cursors.page(
            fetched, page, v -> CursorKey.of(v.period().startDate(), v.period().id().value()));
    return new PeriodPage(
        result.items().stream().map(PeriodController::toDto).map(this::withLockOrder).toList(),
        result.nextCursor());
  }

  /** Gắn lệnh khoá hiện hành của kỳ (không có bộ khoá kỳ trong ngữ cảnh thì để trống). */
  private Period withLockOrder(Period dto) {
    PeriodLockService service = locks.getIfAvailable();
    if (service != null) {
      service
          .current(TenantContext.require(), PeriodId.of(dto.getId()))
          .ifPresent(o -> dto.setCurrentLockOrder(PeriodLockController.toDto(o)));
    }
    return dto;
  }

  static Period toDto(PeriodView view) {
    vn.viettel.okrkpi.config.domain.Period p = view.period();
    Period dto =
        new Period(
            p.id().value(),
            p.code(),
            p.name(),
            PeriodKind.fromValue(p.kind().name()),
            PeriodFlow.fromValue(p.flow().name()),
            p.startDate(),
            p.endDate(),
            PeriodStatus.fromValue(p.state().name()),
            p.rowVersion());
    dto.setParentPeriodId(p.parentId() == null ? null : p.parentId().value());
    dto.setPeriodBucket(p.bucket());
    dto.setPlanDeadline(p.schedule().planDeadline());
    dto.setSubmissionDeadline(p.schedule().submissionDeadline());
    dto.setNotStarted(view.notStarted());
    dto.setLockedAt(p.lockedAt() == null ? null : p.lockedAt().atOffset(ZoneOffset.UTC));
    dto.setLockedBy(p.lockedBy() == null ? null : new Ref(p.lockedBy(), ""));
    dto.setLockSagaId(p.lockSagaId());
    dto.setReopenCount(p.reopenCount());
    vn.viettel.okrkpi.config.domain.PeriodMilestones m = p.schedule().milestones();
    PeriodMilestones milestones = new PeriodMilestones();
    milestones.setPlanApprovalDeadline(m.planApprovalDeadline());
    milestones.setEvidenceDeadline(m.evidenceDeadline());
    milestones.setAcceptanceBacklogDeadline(m.acceptanceBacklogDeadline());
    milestones.setPlannedLockDate(m.plannedLockDate());
    dto.setMilestones(milestones);
    dto.setPinnedPolicyPacks(List.of());
    dto.setCurrentLockOrder(null);
    dto.setWarnings(view.warnings());
    return dto;
  }

  private UUID caller() {
    return callers.current();
  }
}
