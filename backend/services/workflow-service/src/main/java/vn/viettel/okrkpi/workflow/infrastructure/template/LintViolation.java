package vn.viettel.okrkpi.workflow.infrastructure.template;

import java.util.Objects;

/**
 * Một vi phạm của linter mẫu luồng.
 *
 * @param rule quy tắc bị vi phạm
 * @param template mã mẫu (hoặc tên tệp khi chưa đọc được mã)
 * @param detail chi tiết, chỉ gồm mã phần tử BPMN và tên thuộc tính
 */
public record LintViolation(LintRule rule, String template, String detail) {

  public LintViolation {
    Objects.requireNonNull(rule, "rule");
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(detail, "detail");
  }

  @Override
  public String toString() {
    return rule.name() + " [" + template + "] " + detail;
  }
}
