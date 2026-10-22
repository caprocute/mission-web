package vn.viettel.okrkpi.okr.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Chu kỳ check-in của KR theo {@code checkin_frequency} (QT-03 bước 1, CORE-01-11). {@code TUAN} là
 * tuần thứ Hai đến Chủ nhật; {@code HAI_TUAN} là hai tuần liền tính từ thứ Hai của tuần mở kỳ;
 * {@code THANG} là tháng dương lịch (suy luận). Ngày nhắc của một tuần là thứ Năm, nếu thứ Năm là
 * ngày nghỉ thì dời sang ngày làm việc kế tiếp trong tuần (suy luận).
 *
 * @param start ngày đầu chu kỳ
 * @param end ngày cuối chu kỳ (tính cả ngày này)
 */
public record CheckinCycle(LocalDate start, LocalDate end) {

  public static final String TUAN = "TUAN";
  public static final String HAI_TUAN = "HAI_TUAN";
  public static final String THANG = "THANG";

  public CheckinCycle {
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(end, "end");
  }

  /** Chu kỳ chứa {@code day}; {@code periodStart} là mốc của chu kỳ hai tuần. */
  public static CheckinCycle of(String frequency, LocalDate day, LocalDate periodStart) {
    LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    return switch (frequency) {
      case HAI_TUAN -> {
        LocalDate anchor = periodStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long weeks = ChronoUnit.WEEKS.between(anchor, monday);
        LocalDate first = anchor.plusWeeks(Math.floorDiv(weeks, 2) * 2);
        yield new CheckinCycle(first, first.plusDays(13));
      }
      case THANG ->
          new CheckinCycle(day.withDayOfMonth(1), day.with(TemporalAdjusters.lastDayOfMonth()));
      default -> new CheckinCycle(monday, monday.plusDays(6));
    };
  }

  /** Chu kỳ liền trước. */
  public CheckinCycle previous(String frequency, LocalDate periodStart) {
    return of(frequency, start.minusDays(1), periodStart);
  }

  /** Thứ Năm của tuần chứa {@code day}. */
  public static LocalDate thursdayOf(LocalDate day) {
    return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(3);
  }

  /**
   * Ngày nhắc của tuần chứa {@code day}: thứ Năm hoặc ngày làm việc đầu tiên sau đó trong tuần;
   * {@code null} khi từ thứ Năm tới Chủ nhật đều nghỉ.
   */
  public static LocalDate reminderDay(LocalDate day, Predicate<LocalDate> workingDay) {
    LocalDate thursday = thursdayOf(day);
    for (LocalDate d = thursday; !d.isAfter(thursday.plusDays(3)); d = d.plusDays(1)) {
      if (workingDay.test(d)) {
        return d;
      }
    }
    return null;
  }

  /**
   * Tuần có thứ Năm {@code thursday} là tuần nhắc của chu kỳ chứa nó khi thứ Năm kế tiếp đã sang
   * chu kỳ khác: tuần nào với {@code TUAN}, tuần cuối với {@code HAI_TUAN}, tuần có thứ Năm cuối
   * cùng của tháng với {@code THANG} (suy luận).
   */
  public static boolean remindWeek(String frequency, LocalDate thursday, LocalDate periodStart) {
    return thursday.plusDays(7).isAfter(of(frequency, thursday, periodStart).end());
  }
}
