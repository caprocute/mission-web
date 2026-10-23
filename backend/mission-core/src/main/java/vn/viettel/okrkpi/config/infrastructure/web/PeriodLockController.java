package vn.viettel.okrkpi.config.infrastructure.web;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.CRC32;
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
import vn.viettel.okrkpi.config.application.Actor;
import vn.viettel.okrkpi.config.application.PeriodLockControlService;
import vn.viettel.okrkpi.config.application.PeriodLockService;
import vn.viettel.okrkpi.config.domain.LockBatch;
import vn.viettel.okrkpi.config.domain.LockCancelRequest;
import vn.viettel.okrkpi.config.domain.LockEvent;
import vn.viettel.okrkpi.config.domain.LockFailure;
import vn.viettel.okrkpi.config.domain.LockSagaDiagram;
import vn.viettel.okrkpi.config.domain.LockStep;
import vn.viettel.okrkpi.contract.config.model.PeriodLockBatch;
import vn.viettel.okrkpi.contract.config.model.PeriodLockBatchPage;
import vn.viettel.okrkpi.contract.config.model.PeriodLockCancelRequestInput;
import vn.viettel.okrkpi.contract.config.model.PeriodLockCancelState;
import vn.viettel.okrkpi.contract.config.model.PeriodLockControl;
import vn.viettel.okrkpi.contract.config.model.PeriodLockDiagram;
import vn.viettel.okrkpi.contract.config.model.PeriodLockEvent;
import vn.viettel.okrkpi.contract.config.model.PeriodLockEventPage;
import vn.viettel.okrkpi.contract.config.model.PeriodLockFailure;
import vn.viettel.okrkpi.contract.config.model.PeriodLockFailurePage;
import vn.viettel.okrkpi.contract.config.model.PeriodLockOrder;
import vn.viettel.okrkpi.contract.config.model.PeriodLockOrderApprove;
import vn.viettel.okrkpi.contract.config.model.PeriodLockOrderCreate;
import vn.viettel.okrkpi.contract.config.model.PeriodLockOrderStatus;
import vn.viettel.okrkpi.contract.config.model.PeriodLockProgress;
import vn.viettel.okrkpi.contract.config.model.PeriodLockProgressTotals;
import vn.viettel.okrkpi.contract.config.model.PeriodLockRetryRequest;
import vn.viettel.okrkpi.contract.config.model.PeriodLockStart;
import vn.viettel.okrkpi.contract.config.model.PeriodLockStep;
import vn.viettel.okrkpi.contract.config.model.PeriodLockStepStatus;
import vn.viettel.okrkpi.contract.config.model.Ref;
import vn.viettel.okrkpi.contract.config.model.SagaStatus;
import vn.viettel.okrkpi.contract.config.model.ScoreBatchStatus;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Lệnh khoá kỳ của {@code config.yaml} (QT-08, SCR-0801): {@code createPeriodLockOrder}, {@code
 * approvePeriodLockOrder}, {@code startPeriodLock}, {@code getPeriodLockProgress} và chín thao tác
 * theo dõi, điều khiển saga ({@code listPeriodLockBatches}, {@code listPeriodLockFailures}, {@code
 * listPeriodLockEvents}, {@code getPeriodLockDiagram}, {@code pausePeriodLock}, {@code
 * resumePeriodLock}, {@code retryFailedPeriodLock}, {@code requestPeriodLockCancel}, {@code
 * approvePeriodLockCancel}). Quyền theo ma trận dòng SCR-0801 qua OPA; MFA bước lên, tách vai trò
 * và bốn mắt được dịch vụ kiểm lại. {@code If-Match} của thao tác điều khiển là {@code rowVersion}
 * của lệnh khoá kỳ ({@code lockOrder.rowVersion}), ETag phản hồi cũng vậy.
 */
@RestController
@RequestMapping("/api/v1/periods/{id}")
public class PeriodLockController {

  static final String JOB_TYPE = "PERIOD_LOCK";

  private final PeriodLockService locks;
  private final PeriodLockControlService control;
  private final Callers callers;
  private final ConfigIdempotency idempotency;
  private final CursorCodec cursors;

  public PeriodLockController(
      PeriodLockService locks,
      PeriodLockControlService control,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodCaller> periodCallers,
      ConfigIdempotency idempotency,
      CursorCodec cursors) {
    this.locks = Objects.requireNonNull(locks, "locks");
    this.control = Objects.requireNonNull(control, "control");
    this.callers = new Callers(subjects, periodCallers);
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
  }

  @PostMapping("/lock-orders")
  @RequiresPermission(
      action = "SCR-0801.lap-lenh",
      operationId = "createPeriodLockOrder",
      module = "config",
      resourceType = "period_lock_order")
  public ResponseEntity<PeriodLockOrder> createPeriodLockOrder(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody PeriodLockOrderCreate body) {
    TenantId tenant = TenantContext.require();
    Actor actor = callers.actor();
    String path = "/api/v1/periods/" + id + "/lock-orders";
    return idempotency.run(
        tenant,
        actor.userId(),
        idempotencyKey,
        "POST",
        path,
        body,
        PeriodLockOrder.class,
        PeriodLockOrder::getRowVersion,
        () -> {
          vn.viettel.okrkpi.config.domain.PeriodLockOrder order =
              notFound(
                  () ->
                      locks.create(
                          tenant,
                          actor,
                          PeriodId.of(id),
                          body.getBasisDoc(),
                          body.getPlannedLockAt() == null
                              ? null
                              : body.getPlannedLockAt().toInstant()));
          return ResponseEntity.created(URI.create(path + "/" + order.id()))
              .eTag(ETags.of(order.rowVersion()))
              .body(toDto(order));
        });
  }

  @PostMapping("/lock-orders/{oid}/approve")
  @RequiresPermission(
      action = "SCR-0801.duyet-lenh",
      operationId = "approvePeriodLockOrder",
      module = "config",
      resourceType = "period_lock_order")
  public ResponseEntity<PeriodLockOrder> approvePeriodLockOrder(
      @PathVariable("id") UUID id,
      @PathVariable("oid") UUID oid,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) PeriodLockOrderApprove body) {
    TenantId tenant = TenantContext.require();
    Actor actor = callers.actor();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor.userId(),
        idempotencyKey,
        "POST",
        "/api/v1/periods/" + id + "/lock-orders/" + oid + "/approve",
        body,
        PeriodLockOrder.class,
        PeriodLockOrder::getRowVersion,
        () -> {
          vn.viettel.okrkpi.config.domain.PeriodLockOrder order =
              notFound(() -> locks.approve(tenant, actor, PeriodId.of(id), oid, expected));
          return ETags.ok(toDto(order), order.rowVersion());
        });
  }

  @PostMapping("/lock")
  @RequiresPermission(
      action = "SCR-0801.khoi-chay",
      operationId = "startPeriodLock",
      module = "config",
      resourceType = "period_lock_order")
  public ResponseEntity<vn.viettel.okrkpi.shared.job.Job> startPeriodLock(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody PeriodLockStart body) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new vn.viettel.okrkpi.shared.idempotency.IdempotencyExceptions.KeyRequired(false);
    }
    TenantId tenant = TenantContext.require();
    Actor actor = callers.actor();
    Long expected =
        ifMatch == null || ifMatch.isBlank() ? null : ETags.expectedVersion(ifMatch, "CONFIG");
    PeriodLockService.Started started =
        notFound(
            () ->
                locks.start(
                    tenant,
                    actor,
                    PeriodId.of(id),
                    body.getLockOrderId(),
                    idempotencyKey,
                    expected));
    Instant at = started.order().startedAt() == null ? Instant.now() : started.order().startedAt();
    return Jobs.accepted(
        new Job(
            started.jobId(),
            JOB_TYPE,
            JobStatus.DANG_CHAY,
            0,
            "Đang chạy khoá kỳ",
            null,
            null,
            at.atOffset(ZoneOffset.UTC)));
  }

  @GetMapping("/lock")
  @RequiresPermission(
      action = "SCR-0801.xem",
      operationId = "getPeriodLockProgress",
      module = "config",
      resourceType = "period_lock_order")
  public ResponseEntity<PeriodLockProgress> getPeriodLockProgress(@PathVariable("id") UUID id) {
    PeriodLockControlService.Overview p =
        control
            .overview(TenantContext.require(), PeriodId.of(id))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    PeriodLockProgress dto = toDto(id, p);
    CRC32 crc = new CRC32();
    crc.update(
        (p.order().rowVersion() + "|" + p.steps() + "|" + p.totals() + "|" + p.warnings())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return ETags.ok(dto, crc.getValue());
  }

  @GetMapping("/lock/batches")
  @RequiresPermission(
      action = "SCR-0801.xem",
      operationId = "listPeriodLockBatches",
      module = "config",
      resourceType = "period_lock_order")
  public PeriodLockBatchPage listPeriodLockBatches(
      @PathVariable("id") UUID id,
      @RequestParam(value = "status", required = false) ScoreBatchStatus status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    String scope = "period-lock-batches:" + id + ":" + status;
    PageRequest page = cursors.pageRequest(limit, cursor, scope);
    List<LockBatch> fetched =
        notFound(
            () ->
                control.batches(
                    TenantContext.require(),
                    PeriodId.of(id),
                    status == null ? null : status.getValue(),
                    page.afterKey().map(k -> k.getUuid(0)).orElse(null),
                    page.fetchSize()));
    CursorPage<LockBatch> result = cursors.page(fetched, page, b -> CursorKey.of(b.id()));
    return new PeriodLockBatchPage(
        result.items().stream().map(PeriodLockController::toDto).toList(), result.nextCursor());
  }

  @GetMapping("/lock/failures")
  @RequiresPermission(
      action = "SCR-0801.xem",
      operationId = "listPeriodLockFailures",
      module = "config",
      resourceType = "period_lock_order")
  public PeriodLockFailurePage listPeriodLockFailures(
      @PathVariable("id") UUID id,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    String scope = "period-lock-failures:" + id;
    PageRequest page = cursors.pageRequest(limit, cursor, scope);
    List<LockFailure> fetched =
        notFound(
            () ->
                control.failures(
                    TenantContext.require(),
                    PeriodId.of(id),
                    page.afterKey().map(k -> k.getUuid(0)).orElse(null),
                    page.fetchSize()));
    CursorPage<LockFailure> result = cursors.page(fetched, page, f -> CursorKey.of(f.profileId()));
    return new PeriodLockFailurePage(
        result.items().stream().map(PeriodLockController::toDto).toList(), result.nextCursor());
  }

  @GetMapping("/lock/events")
  @RequiresPermission(
      action = "SCR-0801.xem",
      operationId = "listPeriodLockEvents",
      module = "config",
      resourceType = "period_lock_order")
  public PeriodLockEventPage listPeriodLockEvents(
      @PathVariable("id") UUID id,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    String scope = "period-lock-events:" + id;
    PageRequest page = cursors.pageRequest(limit, cursor, scope);
    List<LockEvent> fetched =
        notFound(
            () ->
                control.events(
                    TenantContext.require(),
                    PeriodId.of(id),
                    page.afterKey().map(k -> k.getInstant(0)).orElse(null),
                    page.afterKey().map(k -> k.getUuid(1)).orElse(null),
                    page.fetchSize()));
    CursorPage<LockEvent> result = cursors.page(fetched, page, e -> CursorKey.of(e.at(), e.id()));
    return new PeriodLockEventPage(
        result.items().stream().map(PeriodLockController::toDto).toList(), result.nextCursor());
  }

  @GetMapping("/lock/diagram")
  @RequiresPermission(
      action = "SCR-0801.xem",
      operationId = "getPeriodLockDiagram",
      module = "config",
      resourceType = "period_lock_order")
  public PeriodLockDiagram getPeriodLockDiagram(@PathVariable("id") UUID id) {
    LockSagaDiagram d =
        control
            .diagram(TenantContext.require(), PeriodId.of(id))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    PeriodLockDiagram dto =
        new PeriodLockDiagram(
            d.bpmnXml(),
            new ArrayList<>(d.activeActivityIds()),
            new ArrayList<>(d.completedActivityIds()),
            new ArrayList<>(d.failedActivityIds()));
    dto.setTemplateKey(LockSagaDiagram.TEMPLATE_KEY);
    return dto;
  }

  @PostMapping("/lock/pause")
  @RequiresPermission(
      action = "SCR-0801.dieu-khien",
      operationId = "pausePeriodLock",
      module = "config",
      resourceType = "period_lock_order")
  public ResponseEntity<PeriodLockProgress> pausePeriodLock(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) PeriodLockControl body) {
    String comment = body == null ? null : body.getComment();
    return controlled(
        id,
        "/lock/pause",
        idempotencyKey,
        ifMatch,
        body,
        (tenant, actor, expected) ->
            control.pause(tenant, actor, PeriodId.of(id), expected, comment));
  }

  @PostMapping("/lock/resume")
  @RequiresPermission(
      action = "SCR-0801.dieu-khien",
      operationId = "resumePeriodLock",
      module = "config",
      resourceType = "period_lock_order")
  public ResponseEntity<PeriodLockProgress> resumePeriodLock(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    return controlled(
        id,
        "/lock/resume",
        idempotencyKey,
        ifMatch,
        null,
        (tenant, actor, expected) ->
            control.resume(tenant, actor, PeriodId.of(id), expected, null));
  }

  @PostMapping("/lock/retry-failed")
  @RequiresPermission(
      action = "SCR-0801.dieu-khien",
      operationId = "retryFailedPeriodLock",
      module = "config",
      resourceType = "period_lock_order")
  public ResponseEntity<Job> retryFailedPeriodLock(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) PeriodLockRetryRequest body) {
    TenantId tenant = TenantContext.require();
    Actor actor = callers.actor();
    List<UUID> profiles =
        body == null || body.getProfileIds() == null ? List.of() : body.getProfileIds();
    return idempotency.run(
        tenant,
        actor.userId(),
        idempotencyKey,
        "POST",
        "/api/v1/periods/" + id + "/lock/retry-failed",
        body,
        Job.class,
        j -> 0L,
        () ->
            accepted(
                notFound(() -> control.retryFailed(tenant, actor, PeriodId.of(id), profiles))));
  }

  @PostMapping("/lock/cancel")
  @RequiresPermission(
      action = "SCR-0801.de-nghi-huy",
      operationId = "requestPeriodLockCancel",
      module = "config",
      resourceType = "period_lock_order")
  public ResponseEntity<PeriodLockProgress> requestPeriodLockCancel(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody PeriodLockCancelRequestInput body) {
    String reason = body == null ? null : body.getReason();
    return controlled(
        id,
        "/lock/cancel",
        idempotencyKey,
        ifMatch,
        body,
        (tenant, actor, expected) ->
            control.requestCancel(tenant, actor, PeriodId.of(id), expected, reason));
  }

  @PostMapping("/lock/cancel/approve")
  @RequiresPermission(
      action = "SCR-0801.duyet-huy",
      operationId = "approvePeriodLockCancel",
      module = "config",
      resourceType = "period_lock_order")
  public ResponseEntity<Job> approvePeriodLockCancel(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) PeriodLockControl body) {
    TenantId tenant = TenantContext.require();
    Actor actor = callers.actor();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    String comment = body == null ? null : body.getComment();
    return idempotency.run(
        tenant,
        actor.userId(),
        idempotencyKey,
        "POST",
        "/api/v1/periods/" + id + "/lock/cancel/approve",
        body,
        Job.class,
        j -> 0L,
        () ->
            accepted(
                notFound(
                    () ->
                        control.approveCancel(tenant, actor, PeriodId.of(id), expected, comment))));
  }

  /** Lệnh điều khiển trả tiến trình: kiểm {@code If-Match}, chạy một lần theo Idempotency-Key. */
  private ResponseEntity<PeriodLockProgress> controlled(
      UUID id,
      String suffix,
      String idempotencyKey,
      String ifMatch,
      Object body,
      ControlCall call) {
    TenantId tenant = TenantContext.require();
    Actor actor = callers.actor();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor.userId(),
        idempotencyKey,
        "POST",
        "/api/v1/periods/" + id + suffix,
        body,
        PeriodLockProgress.class,
        p -> p.getLockOrder().getRowVersion(),
        () -> {
          PeriodLockControlService.Overview o = notFound(() -> call.apply(tenant, actor, expected));
          return ETags.ok(toDto(id, o), o.order().rowVersion());
        });
  }

  @FunctionalInterface
  private interface ControlCall {
    PeriodLockControlService.Overview apply(TenantId tenant, Actor actor, long expectedVersion);
  }

  private static ResponseEntity<Job> accepted(PeriodLockControlService.Accepted a) {
    return Jobs.accepted(
        new Job(
            a.jobId().toString(),
            a.jobType(),
            JobStatus.CHO,
            0,
            "Đã ghi nhận yêu cầu, đang chờ tiến trình khoá kỳ xử lý",
            null,
            null,
            a.at().atOffset(ZoneOffset.UTC)));
  }

  static PeriodLockProgress toDto(UUID periodId, PeriodLockControlService.Overview p) {
    PeriodLockProgress dto =
        new PeriodLockProgress(
            periodId,
            toDto(p.order()),
            SagaStatus.fromValue(p.sagaStatus()),
            p.order().pauseRequested(),
            new PeriodLockProgressTotals(
                p.totals().profiles(), p.totals().scored(), p.totals().failed()),
            p.steps().stream().map(PeriodLockController::toDto).toList());
    dto.setTemplateKey(LockSagaDiagram.TEMPLATE_KEY);
    dto.setCancellable(p.cancellable());
    dto.setCancelRequest(toDto(p.order().cancelRequest()));
    dto.setExcludedEvidenceCount(p.excludedEvidence());
    dto.setWarnings(new ArrayList<>(p.warnings()));
    dto.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    return dto;
  }

  private static PeriodLockCancelState toDto(LockCancelRequest c) {
    if (c == null) {
      return null;
    }
    PeriodLockCancelState dto =
        new PeriodLockCancelState(
            new Ref(c.requestedBy(), ""), offset(c.requestedAt()), c.reason());
    dto.setApprovedBy(c.approvedBy() == null ? null : new Ref(c.approvedBy(), ""));
    dto.setApprovedAt(offset(c.approvedAt()));
    return dto;
  }

  private static PeriodLockBatch toDto(LockBatch b) {
    PeriodLockBatch dto =
        new PeriodLockBatch(
            b.id(), b.profileCount(), ScoreBatchStatus.fromValue(b.status()), b.attempts());
    dto.setUnit(b.unitId() == null ? null : new Ref(b.unitId(), ""));
    dto.setFinishedAt(offset(b.finishedAt()));
    return dto;
  }

  private static PeriodLockFailure toDto(LockFailure f) {
    PeriodLockFailure dto = new PeriodLockFailure(f.profileId(), f.error(), f.attempts());
    dto.setPerson(f.personId() == null ? null : new Ref(f.personId(), ""));
    dto.setUnit(f.unitId() == null ? null : new Ref(f.unitId(), ""));
    dto.setBatchId(f.batchId());
    dto.setLastAttemptAt(offset(f.lastAttemptAt()));
    return dto;
  }

  private static PeriodLockEvent toDto(LockEvent e) {
    PeriodLockEvent dto = new PeriodLockEvent(offset(e.at()), e.event());
    dto.setEvt(e.evt());
    dto.setActor(e.actorId() == null ? null : new Ref(e.actorId(), ""));
    dto.setLedgerEntryId(e.ledgerEntryId());
    return dto;
  }

  static PeriodLockOrder toDto(vn.viettel.okrkpi.config.domain.PeriodLockOrder o) {
    PeriodLockOrder dto =
        new PeriodLockOrder(
            o.id(),
            o.periodId().value(),
            PeriodLockOrderStatus.fromValue(o.status().name()),
            o.basisDoc(),
            new Ref(o.requestedBy(), ""),
            o.idempotencyKey(),
            o.rowVersion());
    dto.setPlannedLockAt(offset(o.plannedLockAt()));
    dto.setRequestedAt(offset(o.requestedAt()));
    dto.setApprovedBy(o.approvedBy() == null ? null : new Ref(o.approvedBy(), ""));
    dto.setApprovedAt(offset(o.approvedAt()));
    dto.setStartedBy(o.startedBy() == null ? null : new Ref(o.startedBy(), ""));
    dto.setStartedAt(offset(o.startedAt()));
    dto.setSagaId(o.sagaId());
    return dto;
  }

  private static PeriodLockStep toDto(LockStep s) {
    PeriodLockStep dto =
        new PeriodLockStep(s.stepNo(), s.name(), PeriodLockStepStatus.fromValue(s.status()));
    dto.setStartedAt(offset(s.startedAt()));
    dto.setFinishedAt(offset(s.finishedAt()));
    dto.setResultSummary(new java.util.HashMap<>(s.resultSummary()));
    dto.setCompensationAction(s.compensationAction());
    dto.setCompensatedAt(offset(s.compensatedAt()));
    return dto;
  }

  private static OffsetDateTime offset(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }

  private static <T> T notFound(Supplier<T> work) {
    try {
      return work.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }
}
