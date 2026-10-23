package vn.viettel.okrkpi.dossier.application.dossierpackage.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lệnh sinh biểu mẫu chính thức tới SVC-11 ({@code createDossierRenderJob}, QT-11 bước 10) và trạng
 * thái của lệnh ({@code GET /api/v1/jobs/{id}}). SVC-11 hỏi lại MOD-06 danh sách biểu mẫu {@code
 * CHO_SINH} của gói và dữ liệu đã băm; mỗi tệp xong phát EVT-17. Tệp lỗi không có sự kiện riêng nên
 * MOD-06 đọc trạng thái lệnh để phát hiện (suy luận, wiki trang 14).
 */
public interface RenderRequests {

  /**
   * Gửi lệnh sinh cho gói; trả mã lệnh (mã tác vụ dài của SVC-11).
   *
   * @throws RenderUnavailableException khi SVC-11 không nhận lệnh
   */
  UUID request(TenantId tenant, UUID packageId, String idempotencyKey);

  /** Trạng thái lệnh; rỗng khi SVC-11 không biết lệnh hoặc chưa trả lời được. */
  Optional<RequestState> state(TenantId tenant, UUID requestId);

  /**
   * Trạng thái một lệnh sinh.
   *
   * @param finished mọi tác vụ của lệnh đã xong hoặc lỗi hẳn ({@code HOAN_TAT}, {@code THAT_BAI})
   * @param updatedAt lần đổi trạng thái gần nhất của lệnh
   */
  record RequestState(boolean finished, Instant updatedAt) {}

  /** SVC-11 không nhận lệnh sinh (mất kết nối, 4xx, 5xx). */
  class RenderUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RenderUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
