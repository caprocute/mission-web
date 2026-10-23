package vn.viettel.okrkpi.oversight.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Mở yêu cầu ký văn bản trả lời khiếu nại ở SVC-10 (QT-12 bước 10, nguồn {@code oversight}, mã
 * nguồn là mã khiếu nại). SVC-10 hỏi tài liệu qua cổng nội bộ của MOD-07 và phát EVT-18 khi xong.
 */
@FunctionalInterface
public interface ComplaintResponseSigner {

  /**
   * @param authorization header {@code Authorization} của người ký, có thể {@code null}
   * @return mã yêu cầu ký
   */
  UUID request(TenantId tenant, UUID complaintId, String idempotencyKey, String authorization);
}
