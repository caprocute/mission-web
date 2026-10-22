package vn.viettel.okrkpi.workflow.domain.approval;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * Cộng ngày làm việc theo lịch của tenant: thứ Bảy, Chủ nhật nghỉ, trừ ngày làm bù; ngày lễ, nghỉ
 * bù do lịch nghỉ của tenant khai báo (TL-02a QT-06, {@code config.holiday_calendar}).
 */
public final class WorkingDays {

  /** Giới hạn quét để lịch khai sai không làm treo phép tính. */
  private static final int MAX_SCAN_DAYS = 366;

  private WorkingDays() {}

  /**
   * Ngày làm việc thứ {@code days} sau {@code start} (không tính chính {@code start}).
   *
   * @param overrides ngày khai riêng: {@code true} là ngày làm bù, {@code false} là ngày nghỉ
   */
  public static LocalDate plus(LocalDate start, int days, Map<LocalDate, Boolean> overrides) {
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(overrides, "overrides");
    if (days < 0) {
      throw new IllegalArgumentException("Số ngày làm việc không được âm");
    }
    LocalDate current = start;
    int remaining = days;
    int scanned = 0;
    while (remaining > 0) {
      current = current.plusDays(1);
      scanned++;
      if (scanned > MAX_SCAN_DAYS) {
        throw new IllegalStateException("Lịch không có đủ ngày làm việc trong một năm");
      }
      if (isWorkingDay(current, overrides)) {
        remaining--;
      }
    }
    return current;
  }

  /** Đúng khi {@code day} là ngày làm việc. */
  public static boolean isWorkingDay(LocalDate day, Map<LocalDate, Boolean> overrides) {
    Boolean declared = overrides.get(day);
    if (declared != null) {
      return declared;
    }
    DayOfWeek dow = day.getDayOfWeek();
    return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
  }
}
