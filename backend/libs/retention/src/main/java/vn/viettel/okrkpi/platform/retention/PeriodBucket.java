package vn.viettel.okrkpi.platform.retention;

import java.time.LocalDate;

/**
 * Khoá phân vùng {@code period_bucket} của TL-03 ({@code năm × 10 + quý}, {@code năm × 10} cho dữ
 * liệu năm): ngày kết thúc (không gồm) của kỳ, mốc tính thời hạn nóng và tổng thời hạn.
 */
final class PeriodBucket {

  private PeriodBucket() {}

  static LocalDate end(int bucket) {
    int year = bucket / 10;
    int quarter = bucket % 10;
    if (year < 2000 || year > 2999 || quarter < 0 || quarter > 4) {
      throw new IllegalArgumentException("period_bucket không hợp lệ: " + bucket);
    }
    return quarter == 0
        ? LocalDate.of(year + 1, 1, 1)
        : LocalDate.of(year, quarter * 3, 1).plusMonths(1);
  }
}
