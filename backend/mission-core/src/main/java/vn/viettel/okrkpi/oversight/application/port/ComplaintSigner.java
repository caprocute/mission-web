package vn.viettel.okrkpi.oversight.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Mở yêu cầu ký số nội dung khiếu nại ở SVC-10 (QT-12 bước 2, nguồn {@code oversight}, mã nguồn là
 * mã khiếu nại). SVC-10 hỏi lại tài liệu qua cổng nội bộ của MOD-07 và phát EVT-18 khi ký xong.
 */
public interface ComplaintSigner {

  /**
   * @param authorization header {@code Authorization} của người khiếu nại, có thể {@code null}
   * @return mã yêu cầu ký
   */
  UUID request(TenantId tenant, UUID complaintId, String idempotencyKey, String authorization);
}
