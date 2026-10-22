package vn.viettel.okrkpi.task.domain.plan;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Hạn duyệt kế hoạch quý theo tham số {@code plan_approval_deadline_rule} của Policy Pack
 * (BR-04-01, TL-02a "Lịch nghiệp vụ của một quý", CORE-02-07).
 *
 * <p>{@link #LAST_WORKING_DAY_OF_CALENDAR_WEEK_2}: tuần thứ nhất là tuần lịch (thứ Hai đến Chủ
 * nhật) chứa ngày đầu của tháng đầu quý; hạn là ngày làm việc cuối cùng của tuần thứ hai. Khi cả
 * tuần thứ hai là ngày nghỉ (ví dụ nghỉ Tết), hạn lùi sang ngày làm việc đầu tiên sau tuần đó để
 * đơn vị không mất thời gian duyệt (suy luận). Giá trị tham số lạ được hiểu như quy tắc mặc định.
 */
public final class PlanDeadlineRule {

  public static final String LAST_WORKING_DAY_OF_CALENDAR_WEEK_2 =
      "NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2";

  /** Giới hạn dò ngày làm việc sau tuần thứ hai, đủ cho kỳ nghỉ dài nhất trong năm. */
  private static final int MAX_LOOKAHEAD_DAYS = 31;

  private PlanDeadlineRule() {}

  /**
   * @param rule giá trị tham số, {@code null} là quy tắc mặc định
   * @param periodStart ngày bắt đầu kỳ quý (ngày đầu tháng đầu quý)
   * @param workingDay lịch ngày làm việc của tenant
   */
  public static LocalDate deadline(
      String rule, LocalDate periodStart, Predicate<LocalDate> workingDay) {
    Objects.requireNonNull(periodStart, "periodStart");
    Objects.requireNonNull(workingDay, "workingDay");
    LocalDate firstOfMonth = periodStart.withDayOfMonth(1);
    LocalDate week2Monday =
        firstOfMonth.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1);
    for (LocalDate d = week2Monday.plusDays(6); !d.isBefore(week2Monday); d = d.minusDays(1)) {
      if (workingDay.test(d)) {
        return d;
      }
    }
    LocalDate d = week2Monday.plusDays(7);
    for (int i = 0; i < MAX_LOOKAHEAD_DAYS && !workingDay.test(d); i++) {
      d = d.plusDays(1);
    }
    return d;
  }
}
