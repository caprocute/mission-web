package vn.viettel.okrkpi.oversight.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Mở yêu cầu ký số ở SVC-10 cho quyết định duyệt hoặc từ chối mở lại kỳ (mục đích {@code
 * PHE_DUYET_MO_LAI}, nguồn {@code oversight}, mã nguồn là mã yêu cầu; BR-13-04). SVC-10 hỏi lại tài
 * liệu cần ký qua cổng nội bộ của MOD-07 và phát EVT-18 khi ký xong.
 */
public interface ApprovalSigner {

  /**
   * @param authorization header {@code Authorization} của người duyệt, có thể {@code null}
   * @return mã yêu cầu ký
   */
  UUID request(TenantId tenant, UUID reopenRequestId, String idempotencyKey, String authorization);
}
