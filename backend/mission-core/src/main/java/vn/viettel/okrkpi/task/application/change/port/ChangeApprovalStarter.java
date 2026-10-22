package vn.viettel.okrkpi.task.application.change.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Khởi tạo tiến trình {@code duyet-de-nghi} trên workflow-service cho đề nghị vượt ngưỡng BR-06-06
 * (cùng cơ chế với {@code OkrApprovalStarter} của CORE-01-05): gọi sau khi giao dịch gửi đã commit,
 * workflow-service idempotent theo (tenant, mẫu, {@code TASK_CHANGE_REQUEST:<id>}, {@code
 * rowVersion}); người duyệt thay khi người duyệt vắng quá 2 ngày làm việc do timer của mẫu BPMN
 * (DV-06-03) và {@code wf_binding} xử lý. Chưa có bean thì đề nghị chờ cấp trên duyệt trực tiếp.
 */
public interface ChangeApprovalStarter {

  void start(
      TenantId tenant,
      UUID taskId,
      UUID requestId,
      UUID assignerId,
      long rowVersion,
      UUID submittedBy);
}
