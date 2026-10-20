package vn.viettel.okrkpi.cel.domain;

import java.util.Objects;

/**
 * Ngoại lệ ném ra khi thực thi biểu thức CEL gặp lỗi (thiếu biến bắt buộc, vượt ngưỡng lặp, quá
 * thời gian chạy 50 ms hoặc tham số hàm không hợp lệ lúc chạy).
 */
public final class CelExecutionException extends RuntimeException {

  public static final String CODE_MISSING_VARIABLE = "POLICY-422-CEL-MISSING-VAR";
  public static final String CODE_TIMEOUT = "POLICY-422-CEL-TIMEOUT";
  public static final String CODE_SANDBOX_LIMIT = "POLICY-422-CEL-SANDBOX";
  public static final String CODE_EVAL_ERROR = "POLICY-422-CEL-EVAL";

  private final String errorCode;
  private final String expression;

  public CelExecutionException(
      String errorCode, String expression, String message, Throwable cause) {
    super(errorCode + ": " + message + " (expr: " + expression + ")", cause);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    this.expression = Objects.requireNonNull(expression, "expression must not be null");
  }

  public CelExecutionException(String errorCode, String expression, String message) {
    this(errorCode, expression, message, null);
  }

  public String errorCode() {
    return errorCode;
  }

  public String expression() {
    return expression;
  }
}
