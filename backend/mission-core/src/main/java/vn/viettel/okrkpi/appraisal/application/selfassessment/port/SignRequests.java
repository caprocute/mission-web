package vn.viettel.okrkpi.appraisal.application.selfassessment.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Mở yêu cầu ký ở SVC-10 cho người đang đăng nhập ({@code POST /esign/requests}, mục đích {@code
 * TU_DANH_GIA} hoặc {@code THAM_DINH}); SVC-10 hỏi lại tài liệu cần ký qua cổng nội bộ của MOD-04.
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
   * Mở yêu cầu ký kết luận thẩm định (mục đích {@code THAM_DINH}, CORE-04-05); người ký là người
   * thẩm định đang đăng nhập.
   */
  default UUID requestAppraisal(
      TenantId tenant,
      UUID profileId,
      String provider,
      String idempotencyKey,
      String authorization) {
    throw new IllegalStateException("Chưa cấu hình địa chỉ dịch vụ ký số");
  }

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
