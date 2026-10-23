package vn.viettel.okrkpi.task.infrastructure.web;

import java.net.URI;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.tasks.model.TaskDetail;
import vn.viettel.okrkpi.contract.tasks.model.TaskRegistration;
import vn.viettel.okrkpi.contract.tasks.model.TaskRegistrationDecision;
import vn.viettel.okrkpi.contract.tasks.model.TaskSummary;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.assign.TaskDraftService;
import vn.viettel.okrkpi.task.application.assign.TaskRegistrationService;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;

/**
 * {@code registerTask} và {@code decideTaskRegistration} của {@code tasks.yaml} (QT-05 bước 1c,
 * SCR-0503, CORE-02-17).
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskRegistrationController {

  private final TaskRegistrationService registrations;
  private final TaskTransitionService transitions;
  private final TaskCallers callers;

  public TaskRegistrationController(
      TaskRegistrationService registrations,
      TaskTransitionService transitions,
      TaskCallers callers) {
    this.registrations = Objects.requireNonNull(registrations, "registrations");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.callers = Objects.requireNonNull(callers, "callers");
  }

  @PostMapping("/registrations")
  @Idempotent
  @RequiresPermission(operationId = "registerTask", module = "tasks", resourceType = "task")
  public ResponseEntity<TaskSummary> registerTask(@RequestBody TaskRegistration body) {
    TenantId tenant = TenantContext.require();
    if (body == null || body.getCatalogItemId() == null) {
      throw TaskDraftService.invalid("catalogItemId", "Chưa chọn mục danh mục");
    }
    TaskRow task =
        registrations.registerTask(
            tenant,
            body.getCatalogItemId(),
            body.getQuantity(),
            body.getProposedDueDate(),
            body.getNote(),
            callers.current());
    return ResponseEntity.created(URI.create("/api/v1/tasks/" + task.id()))
        .eTag(ETags.of(task.rowVersion()))
        .body(TaskProgressController.summary(task));
  }

  @PostMapping("/{id}/registration-decision")
  @Idempotent
  @RequiresPermission(
      operationId = "decideTaskRegistration",
      module = "tasks",
      resourceType = "task")
  public ResponseEntity<TaskDetail> decideTaskRegistration(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody TaskRegistrationDecision body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    if (body == null || body.getApprove() == null) {
      throw TaskDraftService.invalid("approve", "Thiếu quyết định duyệt hoặc từ chối");
    }
    TaskRow task;
    try {
      task =
          registrations.decide(
              tenant,
              id,
              expected,
              new TaskRegistrationService.Decision(
                  body.getApprove(), body.getReason(), body.getDueDate(), body.getQuantity()),
              callers.current());
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return ETags.ok(
        TaskTransitionController.detail(task, transitions.stateLog(tenant, task)),
        task.rowVersion());
  }
}
