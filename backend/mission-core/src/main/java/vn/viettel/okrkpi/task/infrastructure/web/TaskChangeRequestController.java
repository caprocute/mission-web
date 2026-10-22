package vn.viettel.okrkpi.task.infrastructure.web;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.tasks.model.CauseKind;
import vn.viettel.okrkpi.contract.tasks.model.ChangeRequest;
import vn.viettel.okrkpi.contract.tasks.model.ChangeRequestDecision;
import vn.viettel.okrkpi.contract.tasks.model.ChangeRequestDraft;
import vn.viettel.okrkpi.contract.tasks.model.ChangeRequestPreview;
import vn.viettel.okrkpi.contract.tasks.model.ChangeRequestPreviewObjective;
import vn.viettel.okrkpi.contract.tasks.model.ChangeRequestPreviewSubjective;
import vn.viettel.okrkpi.contract.tasks.model.ChangeRequestStatus;
import vn.viettel.okrkpi.contract.tasks.model.ChangeRequestType;
import vn.viettel.okrkpi.contract.tasks.model.TaskDetail;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.blocker.TaskBlockerService;
import vn.viettel.okrkpi.task.application.change.TaskChangeDecisionService;
import vn.viettel.okrkpi.task.application.change.TaskChangeRequestService;
import vn.viettel.okrkpi.task.application.change.TaskChangeRequestService.Draft;
import vn.viettel.okrkpi.task.application.change.TaskChangeRequestService.Preview;
import vn.viettel.okrkpi.task.application.change.TaskChangeRequestService.Result;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests.ChangeRow;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;

/**
 * {@code submitTaskChangeRequest}, {@code previewTaskChangeRequest}, {@code
 * withdrawTaskChangeRequest} của {@code tasks.yaml} (QT-06, SCR-0602). Phản hồi {@code TaskDetail}
 * kèm điểm nghẽn, đề nghị và số lần gia hạn đã dùng.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskChangeRequestController {

  private final TaskChangeRequestService changes;
  private final TaskBlockerService blockers;
  private final TaskTransitionService transitions;
  private final TaskCallers callers;
  private final TaskChangeDecisionService decisions;

  public TaskChangeRequestController(
      TaskChangeRequestService changes,
      TaskChangeDecisionService decisions,
      TaskBlockerService blockers,
      TaskTransitionService transitions,
      TaskCallers callers) {
    this.changes = Objects.requireNonNull(changes, "changes");
    this.blockers = Objects.requireNonNull(blockers, "blockers");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.decisions = Objects.requireNonNull(decisions, "decisions");
  }

  @PostMapping("/{id}/change-requests/{requestId}/decision")
  @Idempotent
  @RequiresPermission(
      operationId = "decideTaskChangeRequest",
      module = "tasks",
      resourceType = "task")
  public ResponseEntity<TaskDetail> decideTaskChangeRequest(
      @PathVariable("id") UUID id,
      @PathVariable("requestId") UUID requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody ChangeRequestDecision body) {
    TenantId tenant = TenantContext.require();
    Long expected =
        ifMatch == null || ifMatch.isBlank() ? null : ETags.expectedVersion(ifMatch, "TASK");
    TaskChangeDecisionService.Decision decision =
        body == null
            ? null
            : new TaskChangeDecisionService.Decision(
                body.getApprove(),
                body.getCauseDecided() == null ? null : body.getCauseDecided().getValue(),
                body.getNote());
    TaskChangeDecisionService.Result r =
        found(() -> decisions.decide(tenant, id, requestId, expected, decision, callers.current()));
    return ETags.ok(detail(tenant, r.task()), r.task().rowVersion());
  }

  @PostMapping("/{id}/change-requests")
  @Idempotent
  @RequiresPermission(
      operationId = "submitTaskChangeRequest",
      module = "tasks",
      resourceType = "task")
  public ResponseEntity<TaskDetail> submitTaskChangeRequest(
      @PathVariable("id") UUID id, @RequestBody ChangeRequestDraft body) {
    TenantId tenant = TenantContext.require();
    Result r = found(() -> changes.submitChangeRequest(tenant, id, draft(body), callers.current()));
    return ResponseEntity.created(URI.create("/api/v1/tasks/" + id))
        .eTag(ETags.of(r.task().rowVersion()))
        .body(detail(tenant, r.task()));
  }

  @PostMapping("/{id}/change-requests/preview")
  @RequiresPermission(
      operationId = "previewTaskChangeRequest",
      module = "tasks",
      resourceType = "task")
  public ChangeRequestPreview previewTaskChangeRequest(
      @PathVariable("id") UUID id, @RequestBody ChangeRequestDraft body) {
    TenantId tenant = TenantContext.require();
    Preview p =
        found(() -> changes.previewChangeRequest(tenant, id, draft(body), callers.current()));
    return new ChangeRequestPreview(
        p.extraWorkingDays(),
        new ChangeRequestPreviewObjective(p.objectiveScoreDueDate(), p.objectiveC()),
        new ChangeRequestPreviewSubjective(
            p.subjectiveScoreDueDate(), p.subjectiveDelayDays(), p.subjectiveC()),
        p.overThreshold(),
        p.lateSubmission(),
        p.cancelBlocked());
  }

  @PostMapping("/{id}/change-requests/{requestId}/withdraw")
  @Idempotent
  @RequiresPermission(
      operationId = "withdrawTaskChangeRequest",
      module = "tasks",
      resourceType = "task")
  public ResponseEntity<TaskDetail> withdrawTaskChangeRequest(
      @PathVariable("id") UUID id, @PathVariable("requestId") UUID requestId) {
    TenantId tenant = TenantContext.require();
    Result r = found(() -> changes.withdrawChangeRequest(tenant, id, requestId, callers.current()));
    return ETags.ok(detail(tenant, r.task()), r.task().rowVersion());
  }

  private TaskDetail detail(TenantId tenant, TaskRow task) {
    TaskDetail dto = TaskTransitionController.detail(task, transitions.stateLog(tenant, task));
    dto.setBlockers(
        blockers.listOf(tenant, task).stream().map(TaskBlockerController::dto).toList());
    decorate(dto, changes, tenant, task);
    return dto;
  }

  /** Điền đề nghị và số lần gia hạn vào {@code TaskDetail}. */
  static void decorate(
      TaskDetail dto, TaskChangeRequestService changes, TenantId tenant, TaskRow task) {
    dto.setChangeRequests(
        changes.listOf(tenant, task).stream().map(TaskChangeRequestController::dto).toList());
    int[] ext = changes.extensions(tenant, task);
    dto.setExtensionsUsed(ext[0]);
    dto.setExtensionsMax(ext[1]);
  }

  static ChangeRequest dto(ChangeRow r) {
    ChangeRequest dto =
        new ChangeRequest(
                r.id(),
                ChangeRequestType.fromValue(r.type()),
                ChangeRequestStatus.fromValue(r.status()),
                CauseKind.fromValue(r.causeClaimed() == null ? "CHU_QUAN" : r.causeClaimed()),
                r.reason(),
                "",
                time(r.createdAt()))
            .newDueDate(r.proposedDueDate())
            .newQuantity(r.proposedQuantity())
            .newProduct(r.newProduct())
            .blockerId(r.blockerId())
            .escalated(r.escalated())
            .decidedAt(time(r.decidedAt()))
            .decisionNote(r.decisionNote());
    if (r.causeDecided() != null) {
      dto.causeDecided(CauseKind.fromValue(r.causeDecided()));
    }
    return dto;
  }

  private static Draft draft(ChangeRequestDraft b) {
    if (b == null) {
      return null;
    }
    return new Draft(
        b.getType() == null ? null : b.getType().getValue(),
        b.getNewDueDate(),
        b.getNewQuantity(),
        b.getNewProduct(),
        b.getCauseClaimed() == null ? null : b.getCauseClaimed().getValue(),
        b.getBlockerId(),
        b.getReason());
  }

  private static OffsetDateTime time(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }

  private static <T> T found(Supplier<T> call) {
    try {
      return call.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }
}
