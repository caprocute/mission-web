package vn.viettel.okrkpi.task.application.assign.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * EVT-04 {@code task.task.assigned.v1} và EVT-21 MSG-05-14 tới chủ trì qua outbox cùng giao dịch.
 */
public interface TaskAssignedEvents {

  record Assigned(
      TenantId tenant,
      UUID taskId,
      UUID planItemId,
      String title,
      String code,
      UUID leadAssigneeId,
      List<UUID> coAssigneeIds,
      List<UUID> notifyIds,
      LocalDate dueDate,
      boolean keyTask,
      boolean restricted,
      UUID actorId,
      long aggregateVersion) {}

  void assigned(Assigned event);
}
