package vn.viettel.okrkpi.workflow.domain.binding;

import java.util.Objects;

/**
 * Một vi phạm của linter tham số luồng ({@code WorkflowLintResult.issues[]} của hợp đồng).
 *
 * @param rule mã quy tắc, ví dụ {@code so-cap}
 * @param elementId đường dẫn tham số vi phạm, ví dụ {@code params.cap[2].vai_tro}
 * @param severity {@code error} hoặc {@code warning}
 * @param message thông điệp tiếng Việt cho người cấu hình
 */
public record LintIssue(String rule, String elementId, String severity, String message) {

  public static final String ERROR = "error";
  public static final String WARNING = "warning";

  public LintIssue {
    Objects.requireNonNull(rule, "rule");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(message, "message");
  }

  /** Vi phạm mức lỗi. */
  public static LintIssue error(String rule, String elementId, String message) {
    return new LintIssue(rule, elementId, ERROR, message);
  }

  public boolean isError() {
    return ERROR.equals(severity);
  }
}
