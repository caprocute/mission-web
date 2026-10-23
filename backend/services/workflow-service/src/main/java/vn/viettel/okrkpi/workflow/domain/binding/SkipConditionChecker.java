package vn.viettel.okrkpi.workflow.domain.binding;

import java.util.Map;
import java.util.Optional;

/**
 * Kiểm và tính điều kiện bỏ qua cấp viết bằng CEL (SCR-1504 bảng trường dòng 8): biểu thức phải trả
 * về {@code bool} trên tập biến của hồ sơ mẫu.
 */
public interface SkipConditionChecker {

  /** Thông điệp lỗi kiểu hoặc cú pháp; rỗng khi biểu thức hợp lệ. */
  Optional<String> problem(String expression);

  /** Giá trị của biểu thức hợp lệ trên biến của một hồ sơ mẫu. */
  boolean evaluate(String expression, Map<String, Object> variables);
}
