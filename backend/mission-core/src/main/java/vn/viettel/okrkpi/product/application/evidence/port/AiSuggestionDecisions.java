package vn.viettel.okrkpi.product.application.evidence.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Ghi quyết định của người nộp với gợi ý OCR minh chứng ở SVC-14 ({@code decideAiSuggestion},
 * CORE-03-05) dưới danh tính người nộp: chuyển tiếp {@code Authorization} của người gọi vì SVC-14
 * chỉ cho chính người nhận gợi ý ghi quyết định.
 */
public interface AiSuggestionDecisions {

  /** Quyết định với gợi ý ({@code AiUserAction}); bỏ qua gợi ý không đi qua đường nộp. */
  enum Decision {
    CHAP_NHAN,
    SUA
  }

  /** Gợi ý không có hoặc không thuộc người gọi: không được coi là nội dung do AI điền. */
  class SuggestionNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SuggestionNotFoundException(String message) {
      super(message);
    }
  }

  /**
   * Ghi quyết định; gợi ý đã có quyết định (409) coi là đã ghi, để lần nộp lại sau lỗi không bị
   * chặn.
   *
   * @throws SuggestionNotFoundException khi SVC-14 trả 404 hoặc 403
   * @throws vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException khi SVC-14 quá thời
   *     hạn, quá tải hoặc lỗi
   */
  void decide(
      TenantId tenant,
      UUID suggestionId,
      Decision decision,
      String targetRef,
      String authorization,
      String idempotencyKey);
}
