package vn.viettel.okrkpi.appraisal.domain.selfassessment;

import java.util.Objects;

/**
 * Một lỗi kiểm phiếu theo trường.
 *
 * @param field tên trường của {@code AppraisalSelfAssessmentInput}
 * @param kind loại lỗi, quyết định mã lỗi của hợp đồng
 * @param message thông điệp tiếng Việt cho người dùng
 * @param rule mã quy tắc nghiệp vụ, có thể rỗng
 */
public record SelfAssessmentIssue(String field, Kind kind, String message, String rule) {

  public SelfAssessmentIssue {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(message, "message");
  }

  /** Loại lỗi. */
  public enum Kind {
    /** Thiếu giải trình hoặc giải trình không đủ độ dài (MSG-09-02). */
    REASON,
    /** Thiếu giải pháp đột phá hoặc minh chứng đã xác nhận (MSG-09-03). */
    BREAKTHROUGH,
    /** Lỗi kiểm tra chung theo trường. */
    VALIDATION
  }
}
