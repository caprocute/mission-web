package vn.viettel.okrkpi.workflow.domain.template;

import java.util.Objects;

/**
 * Kết quả một ca test: một kịch bản chạy với một bộ tham số trên engine thử.
 *
 * @param scenario mã kịch bản
 * @param paramsLabel nhãn bộ tham số
 * @param endState trạng thái kết thúc thực tế
 * @param passed kết thúc đúng như mong đợi
 * @param completions số lần hoàn thành user task đã đo
 * @param maxSqlPerCompletion số câu SQL lớn nhất của một lần hoàn thành
 */
public record TestCaseResult(
    String scenario,
    String paramsLabel,
    String endState,
    boolean passed,
    int completions,
    int maxSqlPerCompletion) {

  public TestCaseResult {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(paramsLabel, "paramsLabel");
    Objects.requireNonNull(endState, "endState");
    if (completions < 0 || maxSqlPerCompletion < 0) {
      throw new IllegalArgumentException("Số đo không được âm");
    }
  }
}
