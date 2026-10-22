package vn.viettel.okrkpi.okr.application.port;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng đọc gợi ý AI của SVC-14 ({@code getAiSuggestion} của {@code platform.yaml}) cho CORE-01-13:
 * lõi kiểm gợi ý mà người soạn chèn vào OKR tồn tại, thuộc chính người đó, chưa bị bỏ, và lấy đầu
 * ra để so với nội dung lưu. Gọi ngoài giao dịch CSDL.
 */
public interface OkrAiSuggestions {

  /**
   * Một gợi ý của người gọi.
   *
   * @param kind loại gợi ý con ({@code MUC_TIEU}, {@code SOAT_KR}...), có thể rỗng
   * @param decision quyết định của người dùng ({@code CHAP_NHAN}, {@code SUA}, {@code BO_QUA}) hoặc
   *     rỗng khi chưa quyết định
   * @param output đầu ra đã đọc từ JSON của {@code outputText}; văn bản thường nằm ở khoá {@code
   *     text}
   */
  record Suggestion(UUID id, String kind, String decision, Map<String, Object> output) {}

  /**
   * Gợi ý của người gọi; rỗng khi không có hoặc thuộc người khác (SVC-14 trả 404).
   *
   * @param authorization header {@code Authorization} của người soạn, SVC-14 đọc người gọi từ đó
   * @throws Unavailable khi SVC-14 lỗi, quá tải hoặc chưa cấu hình
   */
  Optional<Suggestion> find(TenantId tenant, UUID user, UUID suggestionId, String authorization);

  /** SVC-14 không trả lời được. */
  final class Unavailable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
