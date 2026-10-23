package vn.viettel.okrkpi.config.application.port;

/**
 * Chuẩn bị chỗ lưu dữ liệu theo kỳ (CORE-08-02, NEN-033): mọi bảng phân vùng LIST theo {@code
 * period_bucket} của các mô-đun phải có phân vùng cho kỳ trước khi kỳ nhận lệnh ghi, vì lược đồ
 * không có phân vùng mặc định ("no partition of relation" khi chèn).
 */
public interface PeriodPartitions {

  /** Không cần chuẩn bị (kiểm thử đơn vị, cấu hình không có CSDL mô-đun). */
  PeriodPartitions NONE = bucket -> {};

  /**
   * Bảo đảm có phân vùng cho {@code bucket} ở mọi bảng theo kỳ; gọi lại không lỗi.
   *
   * @param bucket năm × 10 + quý (quý 0 cho kỳ năm)
   */
  void ensure(int bucket);
}
