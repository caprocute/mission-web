package vn.viettel.okrkpi.task.application.plan.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tạo yêu cầu ký số trên SVC-10 cho phiên bản kế hoạch đang chờ duyệt (CORE-02-09). SVC-10 tự hỏi
 * MOD-02 băm nội dung đã khoá và người được ký qua cổng nguồn ký; bên gọi chỉ chỉ ra phiên bản.
 */
public interface PlanSignatures {

  /**
   * @param signMethod {@code MYSIGN}, {@code USB_TOKEN} hoặc {@code CHUNG_THU_TO_CHUC} (hợp đồng)
   * @param idempotencyKey khoá chống ghi trùng gửi kèm sang SVC-10
   * @param callerToken token truy cập của người duyệt, chuyển tiếp để SVC-10 ghi đúng người ký
   */
  record Request(
      TenantId tenant,
      UUID planId,
      UUID versionId,
      int versionNo,
      String signMethod,
      UUID approver,
      String idempotencyKey,
      String callerToken) {}

  /** Mã yêu cầu ký ({@code esign.sign_request.id}). */
  UUID request(Request request);
}
