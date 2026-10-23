package vn.viettel.okrkpi.workflow.application.periodclose;

import java.time.Duration;
import vn.viettel.okrkpi.workflow.application.saga.StepInvocation;

/** Cảnh báo vận hành của saga chốt kỳ. */
@FunctionalInterface
public interface PeriodCloseAlarms {

  /** Một bước chạy quá ngân sách thời gian (MSG-08-04, NFR-06). */
  void overBudget(StepInvocation invocation, Duration budget, Duration elapsed);

  /** Kiểm tất định phát hiện hồ sơ cho kết quả khác khi tính lại (BR-08-11). */
  default void determinismMismatch(StepInvocation invocation, int profiles) {
    // Mặc định không làm gì; bản lắp ở hạ tầng ghi nhật ký và chỉ số.
  }
}
