package vn.viettel.okrkpi.task.domain.lifecycle;

import java.util.Map;

/** Bộ đánh giá điều kiện CEL của bảng chuyển; cài đặt ở tầng ứng dụng bằng {@code vt-cel}. */
@FunctionalInterface
public interface GuardEvaluator {

  boolean test(String celExpression, Map<String, Object> activation);
}
