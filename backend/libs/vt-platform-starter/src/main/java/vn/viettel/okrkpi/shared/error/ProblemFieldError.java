package vn.viettel.okrkpi.shared.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Lỗi theo trường, đúng lược đồ {@code FieldError} của {@code common.yaml}.
 *
 * @param field tên trường theo JSON của thân yêu cầu, dạng đường dẫn chấm
 * @param message thông điệp tiếng Việt
 * @param rule mã quy tắc nghiệp vụ nếu có, ví dụ {@code BR-05-01}; có thể {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemFieldError(String field, String message, String rule) implements Serializable {

  @Serial private static final long serialVersionUID = 1L;

  public ProblemFieldError {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(message, "message");
  }

  public static ProblemFieldError of(String field, String message) {
    return new ProblemFieldError(field, message, null);
  }

  public static ProblemFieldError of(String field, String message, String rule) {
    return new ProblemFieldError(field, message, rule);
  }
}
