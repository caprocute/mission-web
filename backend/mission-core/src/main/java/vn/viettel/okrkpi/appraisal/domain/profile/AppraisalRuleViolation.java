package vn.viettel.okrkpi.appraisal.domain.profile;

import java.util.Objects;

/** Hồ sơ đánh giá từ chối một lệnh; {@link Code} quyết định mã HTTP ở tầng web. */
public class AppraisalRuleViolation extends RuntimeException {

  /** Lý do từ chối. */
  public enum Code {
    /** Dòng chuyển không có trong bảng (409). */
    TRANSITION_NOT_ALLOWED,
    /** Hồ sơ đã khoá (409). */
    PROFILE_LOCKED,
    /** Thiếu lý do bắt buộc (422). */
    REASON_REQUIRED,
    /** Mức khác mức liền kề mà chưa giải trình, BR-09-12 (422). */
    EXPLANATION_REQUIRED,
    /** Chưa có mức của bước (422). */
    RATING_REQUIRED,
    /** Vượt số lần trả lại (409). */
    RETURN_LIMIT_REACHED,
    /** Vi phạm tách vai trò, BR-09-14 (403). */
    SEPARATION_OF_DUTIES,
    /** Vai trò không được đưa ra mức ở bước này (403). */
    ROLE_NOT_ALLOWED,
    /** Thiếu ảnh chụp điểm (409). */
    SNAPSHOT_REQUIRED
  }

  private final Code code;

  public AppraisalRuleViolation(Code code, String message) {
    super(message);
    this.code = Objects.requireNonNull(code, "code");
  }

  public Code code() {
    return code;
  }
}
