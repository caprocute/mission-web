package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.TaskService;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.port.AssigneeWorkload;

/** Số user task đang mở của từng người trong tenant (chỉ mục {@code ACT_RU_TASK.ASSIGNEE_}). */
@Component
public class FlowableAssigneeWorkload implements AssigneeWorkload {

  private final TaskService tasks;

  public FlowableAssigneeWorkload(TaskService tasks) {
    this.tasks = tasks;
  }

  @Override
  public Map<UUID, Long> openTasks(UUID tenantId, Collection<UUID> people) {
    Map<UUID, Long> out = new HashMap<>();
    for (UUID person : people) {
      out.put(
          person,
          tasks
              .createTaskQuery()
              .taskTenantId(tenantId.toString())
              .taskAssignee(person.toString())
              .count());
    }
    return out;
  }
}
