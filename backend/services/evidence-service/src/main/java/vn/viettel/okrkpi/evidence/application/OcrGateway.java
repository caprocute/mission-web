package vn.viettel.okrkpi.evidence.application;

import java.io.IOException;
import java.time.Instant;

/**
 * Cổng tới ai-gateway (SVC-14) cho OCR nội bộ ({@code ocrEvidenceObject}, {@code POST /ai/ocr}):
 * evidence-service chỉ gửi khoá đọc, băm và loại tệp; ai-gateway tự đọc nội dung qua cổng nội bộ
 * của evidence-service. Không bao giờ gọi cho tệp C3.
 */
public interface OcrGateway {

  /** Yêu cầu OCR theo lược đồ {@code AiOcrRequest}. */
  record Request(String objectKey, String sha256Hex, String mimeType, String classification) {}

  /** Kết quả theo lược đồ {@code AiOcrResult}. */
  record Result(String modelId, String fullText, int pageCount, Instant processedAt) {}

  /** Ảnh không đủ nét hoặc độ tin cậy thấp (422 {@code AI-422-UNREADABLE}). */
  class UnreadableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnreadableException(String message) {
      super(message);
    }
  }

  /** ai-gateway từ chối vĩnh viễn (403, 404, 413 hoặc 4xx khác): không thử lại. */
  class RefusedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RefusedException(String message) {
      super(message);
    }
  }

  /**
   * Lỗi tạm thời (mạng, 5xx, 429) ném {@link IOException} để worker thử lại có giới hạn.
   *
   * @param idempotencyKey khoá {@code Idempotency-Key} của lượt gọi
   */
  Result read(Request request, String idempotencyKey) throws IOException;
}
