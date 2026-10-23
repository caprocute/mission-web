package vn.viettel.okrkpi.config.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Objects;

/**
 * Lịch ngày làm việc đã gộp của một tenant (BR-06-01, BR-15-03): thứ Hai đến thứ Sáu làm việc trừ
 * ngày {@code NGHI_LE}, {@code NGHI_BU}; thứ Bảy, Chủ nhật nghỉ trừ ngày {@code LAM_BU} (suy luận
 * theo lịch Việt Nam). Quy ước đếm khớp hàm CEL {@code ngay_lam_viec} của {@code vt-cel}.
 */
public final class WorkingDays {

  /** Giới hạn bước dò khi cộng ngày làm việc, tránh vòng lặp vô hạn khi lịch sai. */
  static final int MAX_SCAN_DAYS = 3_660;

  private final Map<LocalDate, HolidayDayKind> special;

  public WorkingDays(Map<LocalDate, HolidayDayKind> special) {
    this.special = Map.copyOf(Objects.requireNonNull(special, "special"));
  }

  /** Lịch chỉ nghỉ thứ Bảy, Chủ nhật. */
  public static WorkingDays weekendsOnly() {
    return new WorkingDays(Map.of());
  }

  public Map<LocalDate, HolidayDayKind> specialDays() {
    return special;
  }

  public boolean isWorkingDay(LocalDate date) {
    Objects.requireNonNull(date, "date");
    HolidayDayKind k = special.get(date);
    return k == null ? !HolidayDay.weekend(date) : k.working();
  }

  /**
   * Ngày làm việc thứ {@code n} sau {@code from} ({@code n > 0}) hoặc trước {@code from} ({@code n
   * < 0}); {@code n = 0} trả nguyên {@code from}. Ví dụ hạn nghiệm thu "kết thúc kỳ + 2 ngày làm
   * việc" là {@code plusWorkingDays(end, 2)}.
   */
  public LocalDate plusWorkingDays(LocalDate from, int n) {
    Objects.requireNonNull(from, "from");
    int step = n >= 0 ? 1 : -1;
    int remaining = Math.abs(n);
    LocalDate d = from;
    int scanned = 0;
    while (remaining > 0) {
      d = d.plusDays(step);
      if (++scanned > MAX_SCAN_DAYS) {
        throw new IllegalStateException("Lịch không có đủ ngày làm việc trong 10 năm");
      }
      if (isWorkingDay(d)) {
        remaining--;
      }
    }
    return d;
  }

  /** Ngày làm việc gần nhất không sau {@code date}. */
  public LocalDate workingDayOnOrBefore(LocalDate date) {
    return isWorkingDay(date) ? date : plusWorkingDays(date, -1);
  }

  /**
   * Số ngày làm việc trong khoảng từ sau {@code fromExclusive} đến hết {@code toInclusive}; bằng 0
   * khi {@code fromExclusive >= toInclusive}. Khớp {@code ngay_lam_viec(tu_ngay, den_ngay)}.
   */
  public long countWorkingDays(LocalDate fromExclusive, LocalDate toInclusive) {
    Objects.requireNonNull(fromExclusive, "fromExclusive");
    Objects.requireNonNull(toInclusive, "toInclusive");
    long count = 0;
    for (LocalDate d = fromExclusive.plusDays(1); !d.isAfter(toInclusive); d = d.plusDays(1)) {
      if (isWorkingDay(d)) {
        count++;
      }
    }
    return count;
  }

  /** Số ngày làm việc của từng tháng trong năm (12 phần tử). */
  public int[] workingDaysByMonth(int year) {
    int[] result = new int[12];
    for (int m = 1; m <= 12; m++) {
      YearMonth ym = YearMonth.of(year, m);
      result[m - 1] = (int) countWorkingDays(ym.atDay(1).minusDays(1), ym.atEndOfMonth());
    }
    return result;
  }
}
