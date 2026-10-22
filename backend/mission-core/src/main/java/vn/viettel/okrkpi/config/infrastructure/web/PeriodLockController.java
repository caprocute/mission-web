package vn.viettel.okrkpi.config.infrastructure.web;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.application.Actor;
import vn.viettel.okrkpi.config.application.PeriodLockService;
import vn.viettel.okrkpi.config.domain.LockStep;
import vn.viettel.okrkpi.contract.config.model.PeriodLockOrder;
import vn.viettel.okrkpi.contract.config.model.PeriodLockOrderApprove;
import vn.viettel.okrkpi.contract.config.model.PeriodLockOrderCreate;
import vn.viettel.okrkpi.contract.config.model.PeriodLockOrderStatus;
import vn.viettel.okrkpi.contract.config.model.PeriodLockProgress;
import vn.viettel.okrkpi.contract.config.model.PeriodLockProgressTotals;
import vn.viettel.okrkpi.contract.config.model.PeriodLockStart;
import vn.viettel.okrkpi.contract.config.model.PeriodLockStep;
import vn.viettel.okrkpi.contract.config.model.PeriodLockStepStatus;
import vn.viettel.okrkpi.contract.config.model.Ref;
import vn.viettel.okrkpi.contract.config.model.SagaStatus;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.job.Job;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.Jobs;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Lệnh khoá kỳ của {@code config.yaml} (QT-08, SCR-0801): {@code createPeriodLockOrder}, {@code
 * approvePeriodLockOrder}, {@code startPeriodLock}, {@code getPeriodLockProgress}. Quyền theo ma
 * trận dòng SCR-0801 qua OPA; MFA bước lên và tách vai trò được dịch vụ kiểm lại.
 */
@RestController
@RequestMapping("/api/v1/periods/{id}")
public class PeriodLockController {

  static final String JOB_TYPE = "PERIOD_LOCK";

  private final PeriodLockService locks;
  private final Callers callers;
  private final ConfigIdempotency idempotency;

  public PeriodLockController(
      PeriodLockService locks,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodCaller> periodCallers,
      ConfigIdempotency idempotency) {
    this.locks = Objects.requireNonNull(locks, "locks");
    this.callers = new Callers(subjects, periodCallers);
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
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
    PeriodLockService.Progress p =
        locks
            .progress(TenantContext.require(), PeriodId.of(id))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    PeriodLockProgress dto =
        new PeriodLockProgress(
            id,
            toDto(p.order()),
            sagaStatus(p.order().status()),
            false,
            totals(p.steps().get(2)),
            p.steps().stream().map(PeriodLockController::toDto).toList());
    dto.setTemplateKey("chot-ky");
    dto.setCancellable(p.cancellable());
    dto.setCancelRequest(null);
    dto.setWarnings(java.util.List.of());
    dto.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    CRC32 crc = new CRC32();
    crc.update(
        (p.order().rowVersion() + "|" + p.steps())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return ETags.ok(dto, crc.getValue());
  }

  private static SagaStatus sagaStatus(vn.viettel.okrkpi.config.domain.LockOrderStatus s) {
    return switch (s) {
      case HOAN_TAT -> SagaStatus.HOAN_TAT;
      case DA_HUY -> SagaStatus.DA_BU_TRU;
      default -> SagaStatus.DANG_CHAY;
    };
  }

  private static PeriodLockProgressTotals totals(LockStep scoring) {
    return new PeriodLockProgressTotals(
        intOf(scoring.resultSummary().get("profiles")),
        intOf(scoring.resultSummary().get("scored")),
        intOf(scoring.resultSummary().get("failed")));
  }

  private static int intOf(Object v) {
    return v instanceof Number n ? Math.max(0, n.intValue()) : 0;
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
