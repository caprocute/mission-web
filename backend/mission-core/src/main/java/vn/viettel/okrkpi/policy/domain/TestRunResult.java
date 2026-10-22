package vn.viettel.okrkpi.policy.domain;

/** Kết luận lần chạy kiểm thử ({@code test_run.result}, CHECK {@code ck_test_run__result}). */
public enum TestRunResult {
  /** Đạt toàn bộ ca vàng, hai engine khớp, không vi phạm bất biến (BR-15-07). */
  DAT,
  /** Có ca không đạt, lệch engine, vi phạm bất biến hoặc lỗi khi chạy (MSG-15-06). */
  KHONG_DAT,
  /** Tác vụ {@code TEST_RUN} đang chạy. */
  DANG_CHAY
}
