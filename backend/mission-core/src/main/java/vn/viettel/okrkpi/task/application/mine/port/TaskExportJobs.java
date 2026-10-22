package vn.viettel.okrkpi.task.application.mine.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Theo dõi tác vụ xuất qua {@code GET /jobs/{id}} (loại {@code TASK_EXPORT}). */
public interface TaskExportJobs {

  void accepted(TenantId tenant, String jobId, UUID initiator);

  /** Số tác vụ xuất người này đang chạy, để trả 429 khi gửi dồn. */
  int running(TenantId tenant, UUID initiator);

  void finished(TenantId tenant, String jobId, int rows, String location);

  void failed(TenantId tenant, String jobId, String message);
}
