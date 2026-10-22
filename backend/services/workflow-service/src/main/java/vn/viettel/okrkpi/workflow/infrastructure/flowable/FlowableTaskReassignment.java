package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.port.TaskReassignment;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;

/**
 * {@link TaskReassignment} trên Flowable: người cũ thành {@code owner}, người mới thành {@code
 * assignee} (lịch sử định danh {@code ACT_HI_IDENTITYLINK} ghi cả hai), bình luận loại {@code
 * UY_QUYEN} ghi người thực hiện và lý do; sự kiện {@code TASK_ASSIGNED} cập nhật hộp việc.
 */
@Component
public class FlowableTaskReassignment implements TaskReassignment {

  /** Loại bình luận vết uỷ quyền trong {@code ACT_HI_COMMENT}. */
  public static final String COMMENT_TYPE = "UY_QUYEN";

  /** Biến cục bộ của task: người được giao trước khi chuyển. */
  public static final String FROM_VARIABLE = "uyQuyenTu";

  /** Biến cục bộ của task: lý do chuyển. */
  public static final String REASON_VARIABLE = "uyQuyenLyDo";

  private final TaskService tasks;
  private final RuntimeService runtime;

  public FlowableTaskReassignment(TaskService tasks, RuntimeService runtime) {
    this.tasks = tasks;
    this.runtime = runtime;
  }

  @Override
  public Optional<ReassignableTask> find(UUID tenantId, String taskId) {
    Task task =
        tasks.createTaskQuery().taskId(taskId).taskTenantId(tenantId.toString()).singleResult();
    if (task == null || task.getProcessInstanceId() == null) {
      return Optional.empty();
    }
    Object allowed =
        runtime.getVariable(task.getProcessInstanceId(), ApprovalVariables.CHO_PHEP_UY_QUYEN);
    return Optional.of(
        new ReassignableTask(
            task.getId(),
            tenantId,
            task.getProcessInstanceId(),
            uuid(task.getAssignee()),
            Boolean.TRUE.equals(allowed)));
  }

  @Override
  public void reassign(ReassignableTask task, UUID newApproverId, UUID actorId, String reason) {
    Authentication.setAuthenticatedUserId(actorId.toString());
    try {
      String from =
          task.currentAssigneeId() == null ? "nhóm ứng viên" : task.currentAssigneeId().toString();
      if (task.currentAssigneeId() != null) {
        tasks.setOwner(task.taskId(), task.currentAssigneeId().toString());
      }
      Map<String, Object> local = new HashMap<>();
      local.put(FROM_VARIABLE, from);
      local.put(REASON_VARIABLE, reason);
      tasks.setVariablesLocal(task.taskId(), local);
      tasks.setAssignee(task.taskId(), newApproverId.toString());
      tasks.addComment(
          task.taskId(),
          task.processInstanceId(),
          COMMENT_TYPE,
          "Chuyển người duyệt từ " + from + " sang " + newApproverId + ". Lý do: " + reason);
    } finally {
      Authentication.setAuthenticatedUserId(null);
    }
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
