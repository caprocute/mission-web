package vn.viettel.okrkpi.oversight.application.complaint;

import java.time.DayOfWeek;
import java.time.LocalDate;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Ngày làm việc theo lịch tenant; không có lịch thì thứ Hai đến thứ Sáu (như CORE-07-02). */
final class WorkingDays {

  private final WorkingCalendarApi calendar;

  WorkingDays(WorkingCalendarApi calendar) {
    this.calendar = calendar;
  }

  LocalDate add(TenantId tenant, LocalDate from, int n) {
    if (calendar != null) {
      return calendar.addWorkingDays(tenant, from, n);
    }
    LocalDate d = from;
    int left = n;
    while (left > 0) {
      d = d.plusDays(1);
      if (weekday(d)) {
        left--;
      }
    }
    return d;
  }

  /** Số ngày làm việc còn lại tới {@code deadline}, âm khi đã quá. */
  int left(TenantId tenant, LocalDate today, LocalDate deadline) {
    if (!today.isAfter(deadline)) {
      return (int) count(tenant, today, deadline);
    }
    return (int) -count(tenant, deadline, today);
  }

  private long count(TenantId tenant, LocalDate fromExclusive, LocalDate toInclusive) {
    if (calendar != null) {
      return calendar.countWorkingDays(tenant, fromExclusive, toInclusive);
    }
    long n = 0;
    for (LocalDate d = fromExclusive.plusDays(1); !d.isAfter(toInclusive); d = d.plusDays(1)) {
      if (weekday(d)) {
        n++;
      }
    }
    return n;
  }

  private static boolean weekday(LocalDate d) {
    return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
  }
}
