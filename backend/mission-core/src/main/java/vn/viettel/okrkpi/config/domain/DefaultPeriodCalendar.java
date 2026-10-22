package vn.viettel.okrkpi.config.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Lịch nghiệp vụ mặc định của kỳ theo TL-02a "Lịch nghiệp vụ của một quý", dùng khi Policy Pack
 * chưa cung cấp tham số lịch qua {@code config.api.PeriodCalendarPort}.
 *
 * <ul>
 *   <li>Hạn duyệt kế hoạch ({@code plan_approval_deadline_rule}): ngày làm việc cuối của tuần lịch
 *       thứ hai trong tháng đầu kỳ, tuần thứ nhất là tuần (thứ Hai đến Chủ nhật) chứa ngày đầu
 *       tháng. Quý IV/2026 → 09/10/2026, Quý I/2027 → 08/01/2027.
 *   <li>Hạn nộp minh chứng: ngày cuối kỳ. Hạn nghiệm thu tồn đọng: hai ngày làm việc sau ngày cuối
 *       kỳ. Ngày khoá dự kiến: ngày làm việc kế tiếp (Quý I/2027 → 02/04 và 05/04/2027).
 *   <li>Hạn báo cáo kết quả: ngày làm việc cuối trước ngày 10 của tháng sau kỳ (BR-08-12).
 * </ul>
 *
 * <p>Ngày làm việc ở đây chỉ loại thứ Bảy và Chủ nhật; lịch nghỉ, làm bù của tenant thuộc
 * CORE-08-04 (suy luận, wiki trang 14).
 */
public final class DefaultPeriodCalendar {

  private DefaultPeriodCalendar() {}

  public static PeriodSchedule scheduleOf(LocalDate startDate, LocalDate endDate) {
    LocalDate planApproval = planApprovalDeadline(startDate);
    LocalDate backlog = addWorkingDays(endDate, 2);
    LocalDate lock = addWorkingDays(backlog, 1);
    LocalDate submission = previousWorkingDay(endDate.plusMonths(1).withDayOfMonth(10));
    return new PeriodSchedule(
        planApproval, submission, new PeriodMilestones(planApproval, endDate, backlog, lock));
  }

  static LocalDate planApprovalDeadline(LocalDate startDate) {
    LocalDate firstWeekMonday =
        startDate.withDayOfMonth(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    LocalDate secondWeekSunday = firstWeekMonday.plusDays(13);
    return workingDay(secondWeekSunday) ? secondWeekSunday : previousWorkingDay(secondWeekSunday);
  }

  static LocalDate addWorkingDays(LocalDate from, int days) {
    LocalDate d = from;
    for (int i = 0; i < days; i++) {
      d = d.plusDays(1);
      while (!workingDay(d)) {
        d = d.plusDays(1);
      }
    }
    return d;
  }

  static LocalDate previousWorkingDay(LocalDate before) {
    LocalDate d = before.minusDays(1);
    while (!workingDay(d)) {
      d = d.minusDays(1);
    }
    return d;
  }

  static boolean workingDay(LocalDate d) {
    DayOfWeek w = d.getDayOfWeek();
    return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY;
  }
}
