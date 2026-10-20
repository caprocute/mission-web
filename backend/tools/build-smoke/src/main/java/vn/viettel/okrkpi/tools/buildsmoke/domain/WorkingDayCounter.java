package vn.viettel.okrkpi.tools.buildsmoke.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Lớp mẫu của mô-đun build-smoke (NEN-005): đếm ngày làm việc trong một khoảng, bỏ thứ Bảy và Chủ
 * nhật. Chỉ dùng để chứng minh JaCoCo, PIT và SpotBugs chạy trên gói {@code domain}; không phải
 * lịch ngày làm việc nghiệp vụ (CORE-08-04).
 */
public final class WorkingDayCounter {

  private WorkingDayCounter() {}

  /**
   * Đếm số ngày làm việc trong đoạn [from, to], tính cả hai đầu.
   *
   * @throws IllegalArgumentException khi {@code to} đứng trước {@code from}
   */
  public static int count(LocalDate from, LocalDate to) {
    if (to.isBefore(from)) {
      throw new IllegalArgumentException("Ngày kết thúc đứng trước ngày bắt đầu");
    }
    int days = 0;
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      if (isWorkingDay(d)) {
        days++;
      }
    }
    return days;
  }

  static boolean isWorkingDay(LocalDate day) {
    DayOfWeek dow = day.getDayOfWeek();
    return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
  }
}
