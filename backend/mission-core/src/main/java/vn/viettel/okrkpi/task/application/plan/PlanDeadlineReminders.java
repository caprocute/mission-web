package vn.viettel.okrkpi.task.application.plan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.port.PlanNotices;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PendingPlan;
import vn.viettel.okrkpi.task.domain.plan.PlanSchedule;

/**
 * Nhắc hạn duyệt kế hoạch quý hằng ngày (BR-04-01, CORE-02-07). Kế hoạch có phiên bản 1 chưa duyệt
 * được nhắc người lập và người duyệt (lãnh đạo đơn vị, AC-04) vào ngày làm việc còn đúng 3 ngày làm
 * việc tới hạn, và báo người đứng đầu đơn vị vào ngày làm việc đầu tiên sau hạn (MSG-04-08). Chỉ
 * xét ngày làm việc nên mỗi mốc rơi vào đúng một ngày; khoá giữ chỗ theo ngày chặn gửi lặp khi
 * nhiều bản sao cùng chạy hay chạy lại trong ngày. Quá hạn không chặn duyệt.
 */
public class PlanDeadlineReminders {

  /** Số ngày làm việc nhắc trước hạn (BR-04-01). */
  static final int REMIND_BEFORE = 3;

  private final PlanRepository plans;
  private final PeriodApi periods;
  private final WorkingCalendarApi calendar;
  private final PlanDeadlines deadlines;
  private final PlanNotices notices;
  private final OrgDirectoryApi org;

  public PlanDeadlineReminders(
      PlanRepository plans,
      PeriodApi periods,
      WorkingCalendarApi calendar,
      PlanDeadlines deadlines,
      PlanNotices notices,
      OrgDirectoryApi org) {
    this.plans = Objects.requireNonNull(plans, "plans");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.calendar = Objects.requireNonNull(calendar, "calendar");
    this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
    this.notices = Objects.requireNonNull(notices, "notices");
    this.org = org;
  }

  /** Chạy cho một tenant; trả số thông báo đã ghi. */
  @Transactional(transactionManager = PlanService.TX)
  public int run(TenantId tenant) {
    PeriodSnapshot period = periods.findCurrent(tenant, "QUY").orElse(null);
    LocalDate today = deadlines.today();
    if (period == null || !calendar.isWorkingDay(tenant, today)) {
      return 0;
    }
    LocalDate deadline = deadlines.deadlineOf(tenant, period);
    int left = deadlines.workingDaysTo(tenant, deadline);
    boolean remind = left == REMIND_BEFORE;
    boolean overdue = left == -1;
    if (!remind && !overdue) {
      return 0;
    }
    int sent = 0;
    for (PendingPlan p :
        plans.pendingApproval(
            tenant, PlanSchedule.bucketOf(period.startDate()), period.id().value())) {
      String key = "han-duyet-ke-hoach:" + p.versionId() + (remind ? ":nhac" : ":qua-han");
      if (!plans.claimNotice(tenant, key)) {
        continue;
      }
      List<UUID> heads = org == null ? List.of() : org.headsOf(tenant, p.ownerUnitId(), today);
      if (remind) {
        Set<UUID> to = new LinkedHashSet<>();
        to.add(p.createdBy());
        to.addAll(heads);
        notices.remind(tenant, p, period.name(), deadline, new ArrayList<>(to));
      } else {
        notices.overdue(tenant, p, period.name(), deadline, heads);
      }
      sent++;
    }
    return sent;
  }
}
