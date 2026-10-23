package vn.viettel.okrkpi.workflow.domain.template;

/**
 * Trạng thái lần chạy bộ test ({@code flowable.wf_template_test_run.status}), trùng {@code
 * JobStatus}.
 */
public enum TestRunStatus {
  CHO,
  DANG_CHAY,
  HOAN_TAT,
  THAT_BAI;

  public boolean terminal() {
    return this == HOAN_TAT || this == THAT_BAI;
  }
}
