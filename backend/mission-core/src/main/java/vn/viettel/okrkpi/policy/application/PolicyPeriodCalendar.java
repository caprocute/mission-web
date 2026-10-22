package vn.viettel.okrkpi.policy.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import vn.viettel.okrkpi.config.api.PeriodCalendarPort;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hiện thực cổng {@link PeriodCalendarPort} của MOD-08 (đảo phụ thuộc, CORE-05-03): đọc tham số
 * {@code plan_approval_deadline_rule} của gói hiệu lực cho kỳ quý. Quy tắc {@code
 * NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2} cho hạn duyệt kế hoạch là ngày làm việc cuối cùng của tuần
 * lịch thứ hai, tuần thứ nhất là tuần lịch (thứ Hai đến Chủ nhật) chứa ngày đầu quý (TL-02a QT-04).
 * Các mốc khác để trống để MOD-08 dùng lịch mặc định.
 */
public class PolicyPeriodCalendar implements PeriodCalendarPort {

  public static final String RULE_KEY = "plan_approval_deadline_rule";
  public static final String SECOND_WEEK_RULE = "NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2";

  private final PolicyResolutionService resolution;
  private final Supplier<WorkingCalendarApi> workingDays;

  public PolicyPeriodCalendar(
      PolicyResolutionService resolution, Supplier<WorkingCalendarApi> workingDays) {
    this.resolution = Objects.requireNonNull(resolution, "resolution");
    this.workingDays = Objects.requireNonNull(workingDays, "workingDays");
  }

  @Override
  public Optional<PeriodCalendar> calendarFor(
      TenantId tenantId, String kind, String flow, LocalDate startDate, LocalDate endDate) {
    if (!"QUY".equals(kind) || startDate == null) {
      return Optional.empty();
    }
    for (String f :
        "CA_HAI".equals(flow) ? PolicyResolutionService.FLOWS : java.util.List.of(flow)) {
      Optional<Object> rule =
          resolution
              .selectByRange(tenantId, f, startDate)
              .flatMap(c -> resolution.parameter(tenantId, c.versionId(), RULE_KEY));
      if (rule.isPresent() && SECOND_WEEK_RULE.equals(rule.get())) {
        return Optional.of(
            new PeriodCalendar(
                secondWeekLastWorkingDay(tenantId, startDate), null, null, null, null));
      }
    }
    return Optional.empty();
  }

  LocalDate secondWeekLastWorkingDay(TenantId tenantId, LocalDate quarterStart) {
    LocalDate weekOneMonday = quarterStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    LocalDate day = weekOneMonday.plusWeeks(1).plusDays(6);
    LocalDate monday = weekOneMonday.plusWeeks(1);
    WorkingCalendarApi calendar = workingDays.get();
    while (!day.isBefore(monday)) {
      if (calendar == null
          ? day.getDayOfWeek().getValue() <= 5
          : calendar.isWorkingDay(tenantId, day)) {
        return day;
      }
      day = day.minusDays(1);
    }
    return monday.plusDays(4);
  }
}
