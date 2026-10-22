package vn.viettel.okrkpi.task.application.blocker.port;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-21 cho từng bước của điểm nghẽn, ghi vào outbox cùng giao dịch. */
public interface BlockerNotices {

  record Notice(
      TenantId tenant,
      UUID taskId,
      UUID blockerId,
      long blockerVersion,
      String template,
      List<UUID> recipients,
      Map<String, String> params,
      UUID actorId) {}

  void notice(Notice notice);
}
