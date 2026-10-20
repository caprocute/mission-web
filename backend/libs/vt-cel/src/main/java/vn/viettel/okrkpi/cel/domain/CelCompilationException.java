package vn.viettel.okrkpi.cel.domain;

import java.util.List;
import java.util.Objects;

/**
 * Ngoại lệ ném ra khi biểu thức CEL không hợp lệ lúc biên dịch hoặc kiểm kiểu tĩnh (mã lỗi chuẩn
 * {@code POLICY-422-CEL-TYPE}).
 */
public final class CelCompilationException extends RuntimeException {

  public static final String ERROR_CODE = "POLICY-422-CEL-TYPE";

  private final String errorCode;
  private final String expression;
  private final List<CelIssueDetail> issues;

  public CelCompilationException(String expression, List<CelIssueDetail> issues, Throwable cause) {
    super(buildMessage(expression, issues), cause);
    this.errorCode = ERROR_CODE;
    this.expression = Objects.requireNonNull(expression, "expression must not be null");
    this.issues = List.copyOf(Objects.requireNonNull(issues, "issues must not be null"));
  }

  public CelCompilationException(String expression, List<CelIssueDetail> issues) {
    this(expression, issues, null);
  }

  public String errorCode() {
    return errorCode;
  }

  public String expression() {
    return expression;
  }

  public List<CelIssueDetail> issues() {
    return issues;
  }

  private static String buildMessage(String expression, List<CelIssueDetail> issues) {
    if (issues == null || issues.isEmpty()) {
      return ERROR_CODE + ": Invalid CEL expression: " + expression;
    }
    CelIssueDetail first = issues.get(0);
    return String.format(
        "%s [%s at %d:%d]: %s (expr: %s)",
        ERROR_CODE, first.errorKind(), first.line(), first.column(), first.message(), expression);
  }
}
