package vn.viettel.okrkpi.task.application.change.port;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-21 cho đề nghị điều chỉnh, ghi vào outbox cùng giao dịch. */
public interface ChangeRequestNotices {

  record Notice(
      TenantId tenant,
      UUID taskId,
      UUID requestId,
      long version,
      String template,
      List<UUID> recipients,
      Map<String, String> params,
      UUID actorId) {}

  void notice(Notice notice);
}
