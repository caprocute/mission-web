package vn.viettel.okrkpi.task.infrastructure.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
import vn.viettel.okrkpi.contract.tasks.model.AssigneeRole;
import vn.viettel.okrkpi.contract.tasks.model.StateLogEntry;
import vn.viettel.okrkpi.contract.tasks.model.TaskDetail;
import vn.viettel.okrkpi.contract.tasks.model.TaskDetailAllOfProducts;
import vn.viettel.okrkpi.contract.tasks.model.TaskGroup;
import vn.viettel.okrkpi.contract.tasks.model.TaskNodeKind;
import vn.viettel.okrkpi.contract.tasks.model.TaskSource;
import vn.viettel.okrkpi.contract.tasks.model.TaskStatus;
import vn.viettel.okrkpi.contract.tasks.model.TaskTransitionRequest;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.task.application.change.TaskChangeDecisionService;
import vn.viettel.okrkpi.task.application.lifecycle.TaskDelayRiskService;
import vn.viettel.okrkpi.task.application.lifecycle.TaskTransitionService;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.StateLogRow;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskCommand;

/**
 * {@code transitionTask} của {@code tasks.yaml} (QT-06, SCR-0601): {@code TIEP_TUC} → RESUME,
 * {@code TRINH_NGHIEM_THU} → SUBMIT, {@code HOAN_THANH} → APPROVE. Các lệnh có thao tác riêng
 * (giao, nhận, tạm dừng, huỷ qua đề nghị, nghiệm thu) do task tương ứng gọi {@link
 * TaskTransitionService}. Phản hồi {@code TaskDetail} chỉ đủ các trường bắt buộc mà MOD-02 đã có dữ
 * liệu; phần sản phẩm, vướng mắc, đề nghị do các task CORE-02 sau bổ sung (suy luận).
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskTransitionController {

  private final TaskTransitionService transitions;
  private final TaskCallers callers;
  private final TaskDelayRiskService delayRisks;
  private final TaskChangeDecisionService decisions;

  public TaskTransitionController(
      TaskTransitionService transitions,
      TaskCallers callers,
      TaskDelayRiskService delayRisks,
      TaskChangeDecisionService decisions) {
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.delayRisks = Objects.requireNonNull(delayRisks, "delayRisks");
    this.decisions = Objects.requireNonNull(decisions, "decisions");
  }

  @PostMapping("/{id}/transitions")
  @Idempotent
  @RequiresPermission(operationId = "transitionTask", module = "tasks", resourceType = "task")
  public ResponseEntity<TaskDetail> transitionTask(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody TaskTransitionRequest body) {
    TenantId tenant = TenantContext.require();
    long expected = ETags.expectedVersion(ifMatch, "TASK");
    if (body == null || body.getTransition() == null) {
      throw invalid("transition");
    }
    TaskCommand command =
        switch (body.getTransition()) {
          case TIEP_TUC -> new TaskCommand.Resume();
          case TRINH_NGHIEM_THU -> new TaskCommand.Submit(null);
          case HOAN_THANH -> new TaskCommand.ApproveAcceptance(null);
        };
    TaskActor actor = callers.current();
    TaskRow task;
    try {
      // CORE-02-22: tiếp tục sau tạm dừng dời hạn theo số ngày làm việc đã dừng (BR-06-10).
      task =
          command instanceof TaskCommand.Resume
              ? decisions.resume(tenant, id, expected, actor)
              : transitions.transition(tenant, id, command, actor, expected).task();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    TaskDetail dto = detail(task, transitions.stateLog(tenant, task));
    TaskDelayRiskController.view(delayRisks.find(tenant, task)).ifPresent(dto::delayRisk);
    return ETags.ok(dto, task.rowVersion());
  }

  static TaskDetail detail(TaskRow t, List<StateLogRow> log) {
    TaskDetail dto =
        new TaskDetail(
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
                0,
                t.description() == null ? "" : t.description(),
                "",
                t.scoreDueDate() != null ? t.scoreDueDate() : t.dueDate(),
                new TaskDetailAllOfProducts(0, 0, 0),
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                log.stream().map(TaskTransitionController::entry).toList())
            .keyResultId(t.keyResultId())
            .difficulty(t.difficulty())
            .standardScore(t.standardScore())
            .nodeKind(TaskNodeKind.fromValue(t.nodeKind()))
            .parentTaskId(t.parentTaskId())
            .componentScore(t.componentScore())
            .assignedAt(time(t.assignedAt()));
    return dto;
  }

  private static StateLogEntry entry(StateLogRow r) {
    return new StateLogEntry(
            time(r.occurredAt()),
            r.from() == null ? null : TaskStatus.fromValue(r.from().name()),
            TaskStatus.fromValue(r.to().name()),
            r.transition(),
            "")
        .reason(r.reason());
  }

  private static int percent(BigDecimal ratio) {
    return ratio == null ? 0 : ratio.movePointRight(2).intValue();
  }

  private static OffsetDateTime time(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }

  private static BusinessException invalid(String field) {
    String message = "Thiếu thông tin bắt buộc";
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
