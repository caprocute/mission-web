package vn.viettel.okrkpi.task.infrastructure.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.tasks.model.AssigneeRole;
import vn.viettel.okrkpi.contract.tasks.model.ProblemDetails;
import vn.viettel.okrkpi.contract.tasks.model.ProgressBatchResult;
import vn.viettel.okrkpi.contract.tasks.model.ProgressUpdate;
import vn.viettel.okrkpi.contract.tasks.model.TaskDetail;
import vn.viettel.okrkpi.contract.tasks.model.TaskGroup;
import vn.viettel.okrkpi.contract.tasks.model.TaskSource;
import vn.viettel.okrkpi.contract.tasks.model.TaskStatus;
import vn.viettel.okrkpi.contract.tasks.model.TaskSummary;
import vn.viettel.okrkpi.contract.tasks.model.UpdateTaskProgressBatch200Response;
import vn.viettel.okrkpi.contract.tasks.model.UpdateTaskProgressBatchRequest;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.direct.TaskPermittedActions;
import vn.viettel.okrkpi.task.application.lifecycle.TaskDelayRiskService;
import vn.viettel.okrkpi.task.application.lifecycle.TaskProgressService;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;

/**
 * {@code updateTaskProgress} và {@code updateTaskProgressBatch} của {@code tasks.yaml} (QT-06,
 * SCR-0503, SCR-0504, SCR-0601, CORE-02-19). Thời gian xử lý ghi vào chỉ số {@code
 * mission.task.progress.update} để theo dõi ngân sách 25 ms phía máy chủ (TL-02a "Liên kết thiết
 * kế").
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskProgressController {

  static final String METRIC = "mission.task.progress.update";

  private final TaskProgressService progress;
  private final TaskTransitionService transitions;
  private final TaskDelayRiskService delayRisks;
  private final TaskCallers callers;
  private final Timer single;
  private final Timer batch;

  public TaskProgressController(
      TaskProgressService progress,
      TaskTransitionService transitions,
      TaskDelayRiskService delayRisks,
      TaskCallers callers,
      ObjectProvider<MeterRegistry> meters) {
    this.progress = Objects.requireNonNull(progress, "progress");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.delayRisks = Objects.requireNonNull(delayRisks, "delayRisks");
    this.callers = Objects.requireNonNull(callers, "callers");
    MeterRegistry registry = meters.getIfAvailable();
    this.single =
        registry == null ? null : Timer.builder(METRIC).tag("mode", "single").register(registry);
    this.batch =
        registry == null ? null : Timer.builder(METRIC).tag("mode", "batch").register(registry);
  }

  @PostMapping("/{id}/progress")
  @Idempotent
  @RequiresPermission(operationId = "updateTaskProgress", module = "tasks", resourceType = "task")
  public ResponseEntity<TaskDetail> updateTaskProgress(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) ProgressUpdate body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    TaskActor actor = callers.current();
    TaskProgressService.ProgressInput input =
        body == null
            ? null
            : new TaskProgressService.ProgressInput(
                body.getPercent(),
                body.getNote(),
                body.getDoneQuantity(),
                body.getChannel() == null ? null : body.getChannel().getValue());
    TaskRow task;
    Timer.Sample sample = single == null ? null : Timer.start();
    try {
      task = progress.updateProgress(tenant, id, expected, input, actor, key(key));
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    } finally {
      if (sample != null) {
        sample.stop(single);
      }
    }
    TaskDetail dto = TaskTransitionController.detail(task, transitions.stateLog(tenant, task));
    TaskDelayRiskController.view(delayRisks.find(tenant, task)).ifPresent(dto::delayRisk);
    return ETags.ok(dto, task.rowVersion());
  }

  @PostMapping("/progress-batch")
  @Idempotent
  @RequiresPermission(
      operationId = "updateTaskProgressBatch",
      module = "tasks",
      resourceType = "task")
  public UpdateTaskProgressBatch200Response updateTaskProgressBatch(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) UpdateTaskProgressBatchRequest body) {
    TenantId tenant = TenantContext.require();
    TaskActor actor = callers.current();
    List<TaskProgressService.BatchItem> items =
        body == null || body.getItems() == null
            ? List.of()
            : body.getItems().stream()
                .map(
                    i ->
                        i == null
                            ? null
                            : new TaskProgressService.BatchItem(
                                i.getTaskId(),
                                i.getRowVersion() == null ? null : i.getRowVersion().longValue(),
                                i.getPercent(),
                                i.getNote()))
                .toList();
    Timer.Sample sample = batch == null ? null : Timer.start();
    try {
      return new UpdateTaskProgressBatch200Response(
          progress.applyProgressBatch(tenant, items, actor, key(key)).stream()
              .map(TaskProgressController::result)
              .toList());
    } finally {
      if (sample != null) {
        sample.stop(batch);
      }
    }
  }

  private static ProgressBatchResult result(TaskProgressService.BatchResult r) {
    ProgressBatchResult dto = new ProgressBatchResult(r.taskId(), r.status());
    if (r.task() != null) {
      TaskSummary task = summary(r.task());
      if ("CHU_TRI".equals(r.callerRole()) || "PHOI_HOP".equals(r.callerRole())) {
        task.role(AssigneeRole.fromValue(r.callerRole()));
      }
      dto.task(task.permittedActions(TaskPermittedActions.mine(r.task().status(), r.callerRole())));
    }
    if (r.error() != null) {
      BusinessException e = r.error();
      dto.problem(
          new ProblemDetails()
              .type(e.errorCode().type())
              .title(e.errorCode().title())
              .status(e.errorCode().status())
              .code(e.errorCode().code())
              .detail(e.detail())
              .msg(e.msg()));
    }
    return dto;
  }

  static TaskSummary summary(TaskRow t) {
    return new TaskSummary(
            t.id(),
            t.code(),
            t.title(),
            TaskStatus.fromValue(t.status().name()),
            t.overdue(),
            t.extendedDueDate() != null ? t.extendedDueDate() : t.dueDate(),
            percent(t.progressPct()),
            AssigneeRole.CHU_TRI,
            "",
            "",
            t.periodId(),
            (int) t.rowVersion(),
            TaskGroup.fromValue(t.taskGroup()),
            TaskSource.fromValue(t.source()),
            t.plannedQuantity(),
            t.doneQuantity(),
            t.unit(),
            0,
            0)
        .keyResultId(t.keyResultId());
  }

  private static String key(String key) {
    if (key == null || key.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu Idempotency-Key");
    }
    return key;
  }

  private static int percent(BigDecimal ratio) {
    return ratio == null ? 0 : ratio.movePointRight(2).intValue();
  }
}
