package vn.viettel.okrkpi.workflow.domain.template;

import java.util.Objects;

/**
 * Một vi phạm linter máy chủ của mẫu luồng (quy tắc chống mạng nhện của TL-01 G2 và giới hạn tải
 * của BS-01), trả trong {@code errors[]} của lỗi 422.
 *
 * @param rule mã quy tắc ({@code errors[].rule})
 * @param element mã phần tử BPMN hoặc mã mẫu
 * @param message mô tả cho người soạn
 */
public record TemplateLintFinding(String rule, String element, String message) {

  public TemplateLintFinding {
    Objects.requireNonNull(rule, "rule");
    Objects.requireNonNull(message, "message");
  }
}
