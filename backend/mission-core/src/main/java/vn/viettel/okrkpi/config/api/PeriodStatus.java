package vn.viettel.okrkpi.config.api;

/**
 * Trạng thái vòng đời của một kỳ đánh giá ({@code config.period.status}) theo chuẩn trạng thái
 * baseline mục 9.1, hợp đồng {@code common.yaml#PeriodStatus} và TL-03 {@code
 * fn_check_period_transition}.
 */
public enum PeriodStatus {
  /** Kỳ đang mở, cho phép ghi mục tiêu, nhiệm vụ, điểm số. */
  MO,
  /** Saga chốt kỳ đang chạy; cấm ghi, có thể bù trừ về {@link #MO}. */
  DANG_KHOA,
  /** Kỳ đã khóa sổ; cấm mọi thao tác ghi thuộc kỳ (BR-CFG-01). */
  DA_KHOA,
  /** Mở lại để hậu kiểm sau khi đủ hai phê duyệt; cho phép ghi có kiểm soát. */
  MO_LAI_HAU_KIEM
}
