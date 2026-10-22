package vn.viettel.okrkpi.policy.domain;

/** Trạng thái ca kiểm thử vàng ({@code golden_test_case.status}, BR-15-08). */
public enum GoldenCaseStatus {
  /** Ca mới, chờ người phụ trách nghiệp vụ ký duyệt; chưa tính vào tỷ lệ đạt. */
  NHAP,
  /** Đã ký duyệt; đầu vào và kết quả mong đợi bất biến. */
  DA_DUYET,
  /** Ngừng dùng, đã có ca khác thay thế. */
  NGUNG
}
