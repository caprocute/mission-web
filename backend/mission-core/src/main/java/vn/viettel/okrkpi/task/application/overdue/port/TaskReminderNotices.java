package vn.viettel.okrkpi.task.application.overdue.port;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-21 nhắc việc trên tổng thể nhiệm vụ, ghi vào outbox {@code task.outbox_event}. */
public interface TaskReminderNotices {

  record Notice(
      TenantId tenant,
      UUID taskId,
      long taskVersion,
      String template,
      List<UUID> recipients,
      Map<String, String> params,
      UUID actorId) {}

  void notice(Notice notice);
}
