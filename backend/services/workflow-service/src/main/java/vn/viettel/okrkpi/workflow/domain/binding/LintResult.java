package vn.viettel.okrkpi.workflow.domain.binding;

import java.util.List;

/**
 * Kết quả linter tham số ({@code WorkflowLintResult}): đạt khi không có vi phạm mức lỗi.
 *
 * @param passed đạt
 * @param issues các vi phạm theo thứ tự phát hiện
 */
public record LintResult(boolean passed, List<LintIssue> issues) {

  public LintResult {
    issues = issues == null ? List.of() : List.copyOf(issues);
  }

  public static LintResult of(List<LintIssue> issues) {
    return new LintResult(issues.stream().noneMatch(LintIssue::isError), issues);
  }

  /** Chỉ có vi phạm kiểu CEL ({@link ParamsLinter#CEL_TYPE}). */
  public boolean onlyCelType() {
    List<LintIssue> errors = issues.stream().filter(LintIssue::isError).toList();
    return !errors.isEmpty()
        && errors.stream().allMatch(i -> ParamsLinter.CEL_TYPE.equals(i.rule()));
  }
}
