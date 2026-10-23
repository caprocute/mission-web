package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.api.FlowableTaskAlreadyClaimedException;
import org.flowable.common.engine.impl.db.HasRevision;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskInfo;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.workflow.application.port.TaskEngine;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.CandidateGroup;
import vn.viettel.okrkpi.workflow.domain.Decision;
import vn.viettel.okrkpi.workflow.domain.OpenTask;
import vn.viettel.okrkpi.workflow.domain.dual.DualApproval;

/**
 * {@link TaskEngine} trên API công khai của Flowable 7. Lỗi khoá lạc quan của engine (hai người
 * cùng xử lý một việc) đổi thành 409 {@code WORKFLOW-409-VERSION-CONFLICT} để client thử lại, và
 * được đếm ở chỉ số {@code workflow_task_optimistic_lock_failures_total} (TL-01 G2: tỷ lệ lỗi khoá
 * lạc quan ≤ 0,5%).
 */
@Component
public class FlowableTaskEngine implements TaskEngine {

  /** Biến tiến trình mà mẫu BPMN đọc (TL-01 chương 8: {@code ${decision == 'TRA_LAI'}}). */
  public static final String DECISION_VARIABLE = "decision";

  /** Biến cục bộ của task lưu quyết định cho lịch sử. */
  public static final String TASK_DECISION_VARIABLE = "taskDecision";

  /** Biến cục bộ của task lưu ý kiến cho lịch sử. */
  public static final String TASK_COMMENT_VARIABLE = "taskComment";

  private final TaskService tasks;
  private final RuntimeService runtime;
  private final HistoryService history;
  private final Clock clock;
  private final Counter lockFailures;
  private final Counter completions;

  public FlowableTaskEngine(
      TaskService tasks,
      RuntimeService runtime,
      HistoryService history,
      Clock clock,
      MeterRegistry meters) {
    this.tasks = tasks;
    this.runtime = runtime;
    this.history = history;
    this.clock = clock;
    this.lockFailures =
        Counter.builder("workflow_task_optimistic_lock_failures")
            .description("Số lần hoàn thành user task gặp xung đột khoá lạc quan của engine")
            .register(meters);
    this.completions =
        Counter.builder("workflow_task_completions")
            .description("Số user task đã hoàn thành qua API")
            .register(meters);
  }

  @Override
  public Optional<OpenTask> findOpen(UUID tenantId, String taskId) {
    Task task =
        tasks.createTaskQuery().taskId(taskId).taskTenantId(tenantId.toString()).singleResult();
    if (task == null || task.getProcessInstanceId() == null) {
      return Optional.empty();
    }
    ProcessInstance instance =
        runtime
            .createProcessInstanceQuery()
            .processInstanceId(task.getProcessInstanceId())
            .singleResult();
    if (instance == null) {
      return Optional.empty();
    }
    // Việc của tiến trình con gọi qua call activity (duyệt kép): đối tượng và người trình là của
    // tiến trình gốc.
    ProcessInstance root = root(instance);
    Optional<BusinessKey> key =
        BusinessKey.parse(
            instance.getBusinessKey() != null ? instance.getBusinessKey() : root.getBusinessKey());
    if (key.isEmpty()) {
      return Optional.empty();
    }
    List<CandidateGroup> candidates =
        tasks.getIdentityLinksForTask(taskId).stream()
            .filter(l -> IdentityLinkType.CANDIDATE.equals(l.getType()))
            .map(IdentityLink::getGroupId)
            .map(CandidateGroup::parse)
            .flatMap(Optional::stream)
            .toList();
    return Optional.of(
        new OpenTask(
            task.getId(),
            tenantId,
            uuid(task.getAssignee()),
            candidates,
            revision(task),
            key.get(),
            uuid(
                instance.getStartUserId() != null
                    ? instance.getStartUserId()
                    : root.getStartUserId())));
  }

  private ProcessInstance root(ProcessInstance instance) {
    String rootId = instance.getRootProcessInstanceId();
    if (rootId == null || rootId.equals(instance.getId())) {
      return instance;
    }
    ProcessInstance root =
        runtime.createProcessInstanceQuery().processInstanceId(rootId).singleResult();
    return root == null ? instance : root;
  }

  @Override
  public Optional<List<UUID>> otherBranchApprovers(UUID tenantId, String taskId) {
    Task task =
        tasks.createTaskQuery().taskId(taskId).taskTenantId(tenantId.toString()).singleResult();
    if (task == null
        || task.getProcessDefinitionId() == null
        || !task.getProcessDefinitionId().startsWith(DualApproval.TEMPLATE + ":")) {
      return Optional.empty();
    }
    return Optional.of(
        history
            .createHistoricTaskInstanceQuery()
            .processInstanceId(task.getProcessInstanceId())
            .finished()
            .list()
            .stream()
            .filter(t -> !task.getTaskDefinitionKey().equals(t.getTaskDefinitionKey()))
            .map(t -> uuid(t.getAssignee()))
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList());
  }

  @Override
  public boolean wasCompleted(UUID tenantId, String taskId) {
    return history
            .createHistoricTaskInstanceQuery()
            .taskId(taskId)
            .taskTenantId(tenantId.toString())
            .finished()
            .count()
        > 0;
  }

  @Override
  public Completion complete(OpenTask task, Approver approver, Decision decision, String comment) {
    try {
      if (task.needsClaim()) {
        tasks.claim(task.taskId(), approver.actorId().toString());
      }
      Map<String, Object> local = new HashMap<>();
      local.put(TASK_DECISION_VARIABLE, decision.name());
      if (comment != null) {
        local.put(TASK_COMMENT_VARIABLE, comment);
      }
      tasks.setVariablesLocal(task.taskId(), local);
      String processInstanceId =
          tasks.createTaskQuery().taskId(task.taskId()).singleResult().getProcessInstanceId();
      tasks.complete(task.taskId(), Map.of(DECISION_VARIABLE, decision.name()));
      completions.increment();
      boolean ended =
          runtime.createProcessInstanceQuery().processInstanceId(processInstanceId).count() == 0;
      return new Completion(clock.instant(), ended);
    } catch (FlowableOptimisticLockingException
        | FlowableTaskAlreadyClaimedException
        | FlowableObjectNotFoundException e) {
      // Người khác vừa nhận, hoàn thành hoặc sửa việc giữa lúc đọc và lúc ghi.
      lockFailures.increment();
      throw new VersionConflictException("WORKFLOW", (long) task.revision(), null);
    }
  }

  private static int revision(TaskInfo task) {
    return task instanceof HasRevision r ? r.getRevision() : 1;
  }

  private static UUID uuid(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
