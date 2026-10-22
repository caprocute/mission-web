package vn.viettel.okrkpi.task.infrastructure.web;

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
import vn.viettel.okrkpi.contract.tasks.model.ReassignTaskRequest;
import vn.viettel.okrkpi.contract.tasks.model.RemindTaskRequest;
import vn.viettel.okrkpi.contract.tasks.model.TaskDetail;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.blocker.TaskBlockerService;
import vn.viettel.okrkpi.task.application.change.TaskChangeRequestService;
import vn.viettel.okrkpi.task.application.direct.TaskDirectionService;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;

/** {@code remindTask}, {@code reassignTask} của {@code tasks.yaml} (QT-16 bước 4, SCR-1601). */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskDirectionController {

  private final TaskDirectionService directions;
  private final TaskTransitionService transitions;
  private final TaskBlockerService blockers;
  private final TaskChangeRequestService changes;
  private final TaskCallers callers;

  public TaskDirectionController(
      TaskDirectionService directions,
      TaskTransitionService transitions,
      TaskBlockerService blockers,
      TaskChangeRequestService changes,
      TaskCallers callers) {
    this.directions = Objects.requireNonNull(directions, "directions");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.blockers = Objects.requireNonNull(blockers, "blockers");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @PostMapping("/{id}/reminders")
  @Idempotent
  @RequiresPermission(operationId = "remindTask", module = "tasks", resourceType = "task")
  public ResponseEntity<Void> remindTask(
      @PathVariable("id") UUID id, @RequestBody(required = false) RemindTaskRequest body) {
    TenantId tenant = TenantContext.require();
    String note = body == null ? null : body.getNote();
    found(
        () -> {
          directions.remindTask(tenant, id, note, callers.current());
          return null;
        });
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/{id}/reassign")
  @Idempotent
  @RequiresPermission(operationId = "reassignTask", module = "tasks", resourceType = "task")
  public ResponseEntity<TaskDetail> reassignTask(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody ReassignTaskRequest body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    TaskDirectionService.Reassigned r =
        found(
            () ->
                directions.reassignTask(
                    tenant,
                    id,
                    expected,
                    body == null ? null : body.getLeadPersonId(),
                    body == null ? null : body.getReason(),
                    callers.current()));
    TaskRow t = r.task();
    TaskDetail dto = TaskTransitionController.detail(t, transitions.stateLog(tenant, t));
    dto.setBlockers(blockers.listOf(tenant, t).stream().map(TaskBlockerController::dto).toList());
    TaskChangeRequestController.decorate(dto, changes, tenant, t);
    return ETags.ok(dto, t.rowVersion());
  }

  private static <T> T found(Supplier<T> call) {
    try {
      return call.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }
}
