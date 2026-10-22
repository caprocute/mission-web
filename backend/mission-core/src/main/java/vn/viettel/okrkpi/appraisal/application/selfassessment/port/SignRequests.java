package vn.viettel.okrkpi.appraisal.application.selfassessment.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Mở yêu cầu ký ở SVC-10 cho người đang đăng nhập ({@code POST /esign/requests}, mục đích {@code
 * TU_DANH_GIA}); SVC-10 hỏi lại tài liệu cần ký qua cổng nội bộ của MOD-04.
 */
public interface SignRequests {

  /**
   * @param provider nhà cung cấp chữ ký, {@code null} dùng mặc định
   * @param authorization header {@code Authorization} của người ký (SVC-10 kiểm step-up và người
   *     ký), có thể {@code null}
   * @return mã yêu cầu ký
   */
  UUID requestSelfAssessment(
      TenantId tenant,
      UUID profileId,
      String provider,
      String idempotencyKey,
      String authorization);

  /**
   * Lỗi SVC-10 trả nguyên cho client (ví dụ 401 cần xác thực bổ sung): {@code getCause()} là ngoại
   * lệ cần ném lại.
   */
  final class Relayed extends RuntimeException {
    public Relayed(RuntimeException cause) {
      super(cause.getMessage(), cause, false, false);
    }
  }
}
