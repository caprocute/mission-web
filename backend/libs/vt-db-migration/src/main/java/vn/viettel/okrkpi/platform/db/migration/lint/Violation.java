package vn.viettel.okrkpi.platform.db.migration.lint;

/**
 * Một vi phạm quy ước.
 *
 * @param rule luật bị vi phạm
 * @param object đối tượng vi phạm, dạng {@code schema.bảng[.cột]} hoặc tên ràng buộc
 * @param message mô tả cách sửa
 */
public record Violation(LintRule rule, String object, String message) {

  @Override
  public String toString() {
    return "[" + rule + "] " + object + ": " + message;
  }
}
