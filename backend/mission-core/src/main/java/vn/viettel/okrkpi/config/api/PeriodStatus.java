package vn.viettel.okrkpi.config.api;

/**
 * Trạng thái vòng đời của một kỳ đánh giá ({@code config.period.status}) theo chuẩn trạng thái
 * baseline và TL-02.
 */
public enum PeriodStatus {
  /** Kỳ mới khởi tạo, chưa mở cho đăng ký mục tiêu / giao việc. */
  CHUA_MO,
  /** Kỳ đang mở hoạt động bình thường. */
  DANG_HOAT_DONG,
  /** Kỳ đã khóa sổ; cấm mọi thao tác ghi sửa mục tiêu, nhiệm vụ, điểm số thuộc kỳ (BR-CFG-01). */
  DA_KHOA
}
