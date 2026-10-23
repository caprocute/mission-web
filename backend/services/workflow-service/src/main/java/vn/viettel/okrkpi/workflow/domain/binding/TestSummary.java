package vn.viettel.okrkpi.workflow.domain.binding;

/**
 * Kết quả bộ hồ sơ mẫu chạy trên engine thử ({@code WorkflowTestSummary}).
 *
 * @param passed số ca đạt
 * @param total tổng số ca
 */
public record TestSummary(int passed, int total) {

  public TestSummary {
    if (passed < 0 || total < 0 || passed > total) {
      throw new IllegalArgumentException("Số ca kiểm thử không hợp lệ");
    }
  }

  public boolean allPassed() {
    return passed == total;
  }
}
