package vn.viettel.okrkpi.shared.error;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * Ngoại lệ nghiệp vụ mang một mã của {@link ErrorCatalog}; bộ xử lý lỗi chung trả nó thành Problem
 * Details với {@code type}, {@code title}, {@code status}, {@code code}, {@code msg} của mã. Chỉ
 * nhận mã có trong danh mục sinh từ hợp đồng: mã lạ ném {@link IllegalArgumentException} ngay khi
 * dựng.
 *
 * <p>{@code getMessage()} chỉ là mã lỗi; {@code detail} là câu tiếng Việt cho người dùng, không
 * chứa dữ liệu C2/C3 hay thông tin nội bộ.
 */
public class BusinessException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final ErrorCode errorCode;
  private final String detail;
  private final String msg;
  private final List<ProblemFieldError> errors;

  public BusinessException(String code) {
    this(code, null, List.of(), null);
  }

  public BusinessException(String code, String detail) {
    this(code, detail, List.of(), null);
  }

  public BusinessException(String code, String detail, List<ProblemFieldError> errors) {
    this(code, detail, errors, null);
  }

  /**
   * @param code mã trong danh mục
   * @param detail mô tả tiếng Việt, có thể {@code null}
   * @param errors lỗi theo trường, có thể rỗng
   * @param msg mã thông điệp TL-02 thay cho mã mặc định của danh mục, có thể {@code null}
   */
  public BusinessException(String code, String detail, List<ProblemFieldError> errors, String msg) {
    this(ErrorCatalog.standard().require(code), detail, errors, msg);
  }

  /** Dựng từ một {@link ErrorCode} đã có (lớp con dùng cho mã dựng theo mẫu). */
  protected BusinessException(
      ErrorCode errorCode, String detail, List<ProblemFieldError> errors, String msg) {
    super(Objects.requireNonNull(errorCode, "errorCode").code(), null, false, false);
    if (msg != null && !ErrorCode.MSG_PATTERN.matcher(msg).matches()) {
      throw new IllegalArgumentException("msg sai dạng MSG-xx-yy: " + msg);
    }
    this.errorCode = errorCode;
    this.detail = detail;
    this.msg = msg != null ? msg : errorCode.msg();
    this.errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  public String detail() {
    return detail;
  }

  /** Mã MSG sẽ trả ở trường {@code msg}, có thể {@code null}. */
  public String msg() {
    return msg;
  }

  public List<ProblemFieldError> errors() {
    return errors;
  }
}
