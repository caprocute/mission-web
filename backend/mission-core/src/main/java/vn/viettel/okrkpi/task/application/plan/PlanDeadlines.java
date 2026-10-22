package vn.viettel.okrkpi.task.application.plan;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.VersionRow;
import vn.viettel.okrkpi.task.domain.plan.PlanDeadlineRule;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus;

/**
 * Hạn duyệt kế hoạch quý (BR-04-01, CORE-02-07). {@code config.period} thuộc MOD-08 nên MOD-02
 * không ghi hạn vào đó mà tính khi đọc từ tham số {@code plan_approval_deadline_rule} và lịch ngày
 * làm việc của tenant, đệm theo (tenant, kỳ); lịch nghỉ đổi thì {@link #evict} xoá đệm của tenant.
 */
public class PlanDeadlines {

  static final String RULE_KEY = "plan_approval_deadline_rule";
  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private record Key(TenantId tenant, java.util.UUID periodId) {}

  private final WorkingCalendarApi calendar;
  private final PolicyQueryApi policies;
  private final Clock clock;
  private final Map<Key, LocalDate> cache = new ConcurrentHashMap<>();

  public PlanDeadlines(WorkingCalendarApi calendar, PolicyQueryApi policies, Clock clock) {
    this.calendar = Objects.requireNonNull(calendar, "calendar");
    this.policies = policies;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Hạn duyệt của kế hoạch quý trong kỳ. */
  public LocalDate deadlineOf(TenantId tenant, PeriodSnapshot period) {
    return cache.computeIfAbsent(
        new Key(tenant, period.id().value()),
        k ->
            PlanDeadlineRule.deadline(
                rule(tenant, period), period.startDate(), d -> calendar.isWorkingDay(tenant, d)));
  }

  /** Ngày nghiệp vụ hôm nay theo giờ Việt Nam. */
  public LocalDate today() {
    return LocalDate.now(clock.withZone(ZONE));
  }

  /** Số ngày làm việc từ hôm nay tới hạn; âm là số ngày làm việc đã quá hạn, 0 là đúng ngày hạn. */
  public int workingDaysTo(TenantId tenant, LocalDate deadline) {
    LocalDate today = today();
    return today.isAfter(deadline)
        ? -(int) calendar.countWorkingDays(tenant, deadline, today)
        : (int) calendar.countWorkingDays(tenant, today, deadline);
  }

  /**
   * Kế hoạch duyệt muộn (dấu hiệu cho bảng điều hành và QT-14): phiên bản 1 duyệt sau hạn, hoặc
   * chưa duyệt khi đã qua hạn. Phiên bản điều chỉnh không xét hạn này (suy luận).
   */
  public boolean late(TenantId tenant, PeriodSnapshot period, VersionRow version) {
    if (version.versionNo() != 1) {
      return false;
    }
    LocalDate deadline = deadlineOf(tenant, period);
    if (version.status() == PlanVersionStatus.DA_DUYET) {
      return version.approvedAt() != null
          && version.approvedAt().atZone(ZONE).toLocalDate().isAfter(deadline);
    }
    return today().isAfter(deadline);
  }

  /** Lịch nghỉ, làm bù của tenant đổi: hạn được tính lại ở lần đọc sau. */
  public void evict(TenantId tenant) {
    cache.keySet().removeIf(k -> k.tenant().equals(tenant));
  }

  private String rule(TenantId tenant, PeriodSnapshot period) {
    if (policies == null) {
      return null;
    }
    for (String flow : PlanService.FLOWS) {
      Optional<ResolvedPack> pack = policies.resolve(tenant, period.id(), flow, null, null);
      if (pack.isPresent()) {
        return policies
            .parameter(tenant, pack.get().versionId(), RULE_KEY)
            .map(String::valueOf)
            .orElse(null);
      }
    }
    return null;
  }
}
