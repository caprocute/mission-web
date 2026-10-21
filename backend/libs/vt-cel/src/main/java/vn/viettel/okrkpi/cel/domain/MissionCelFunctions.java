package vn.viettel.okrkpi.cel.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Objects;

/**
 * Các hàm mở rộng chuẩn của Mission cho CEL (TL-01 §4.3, TL-02 BR-021..028):
 *
 * <ul>
 *   <li>{@code sum(list)}: tổng phần tử số trong danh sách (danh sách rỗng = {@code 0.0}), cộng
 *       bằng {@link BigDecimal} để tránh sai số dấu phẩy động.
 *   <li>{@code ngay_lam_viec(tu, den)} / {@code workdays(tu, den)}: đếm số ngày làm việc trong nửa
 *       khoảng {@code (tu, den]} theo {@link WorkingCalendar}.
 *   <li>{@code min_muc(a, b)}: trả về mức xếp loại thấp hơn theo thứ tự {@code KHTNV < HTNV < HTTNV
 *       < HTXSNV} (giữ nguyên định dạng mã ngắn hoặc dài theo tham số đầu tiên).
 * </ul>
 */
public final class MissionCelFunctions {

  private MissionCelFunctions() {}

  /**
   * Tính tổng danh sách số bằng {@link BigDecimal} và trả về {@code Double}.
   *
   * @param values danh sách các phần tử {@link Number}
   * @return tổng kiểu {@code Double}; trả về {@code 0.0} nếu danh sách rỗng
   */
  public static Double sum(Collection<?> values) {
    Objects.requireNonNull(values, "sum(list) argument must not be null");
    if (values.isEmpty()) {
      return 0.0d;
    }
    BigDecimal accumulator = BigDecimal.ZERO;
    for (Object item : values) {
      if (!(item instanceof Number number)) {
        throw new IllegalArgumentException(
            "sum(list) requires numeric elements, but got: "
                + (item == null ? "null" : item.getClass().getSimpleName()));
      }
      accumulator = accumulator.add(CelRounding.toBigDecimal(number));
    }
    return accumulator.doubleValue();
  }

  /**
   * Đếm số ngày làm việc giữa hai chuỗi ngày ISO-8601 {@code YYYY-MM-DD} theo {@link
   * WorkingCalendar}.
   *
   * @param fromIso ngày bắt đầu (không tính)
   * @param toIso ngày kết thúc (có tính)
   * @param calendar lịch ngày làm việc
   * @return số ngày làm việc kiểu {@code Long} (tương ứng kiểu {@code int} của CEL)
   */
  public static Long ngayLamViec(String fromIso, String toIso, WorkingCalendar calendar) {
    Objects.requireNonNull(calendar, "calendar must not be null");
    return calendar.countWorkingDaysIso(fromIso, toIso);
  }

  /**
   * Trả về mức xếp loại thấp hơn giữa {@code a} và {@code b}. Nếu tham số đầu tiên dùng mã dài
   * ({@code KHONG_HOAN_THANH}, {@code HOAN_THANH}, {@code HOAN_THANH_TOT}, {@code
   * HOAN_THANH_XUAT_SAC}) thì kết quả cũng trả về mã dài; ngược lại trả về mã chuẩn ngắn ({@code
   * KHTNV}, {@code HTNV}, {@code HTTNV}, {@code HTXSNV}).
   */
  public static String minMuc(String a, String b) {
    return RatingLevel.minMuc(a, b);
  }
}
