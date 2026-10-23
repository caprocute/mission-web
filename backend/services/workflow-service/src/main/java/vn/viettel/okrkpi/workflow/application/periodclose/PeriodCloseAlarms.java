package vn.viettel.okrkpi.workflow.application.periodclose;

import java.time.Duration;
import vn.viettel.okrkpi.workflow.application.saga.StepInvocation;

/** Cảnh báo vận hành khi một bước chốt kỳ chạy quá ngân sách thời gian (MSG-08-04, NFR-06). */
@FunctionalInterface
public interface PeriodCloseAlarms {

  void overBudget(StepInvocation invocation, Duration budget, Duration elapsed);
}
