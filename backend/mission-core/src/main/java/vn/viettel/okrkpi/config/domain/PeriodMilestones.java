package vn.viettel.okrkpi.config.domain;

import java.time.LocalDate;

/**
 * Lịch mốc của kỳ ({@code config.period.milestones}, TL-02a SCR-0801 trường 2). Mốc nào cũng có thể
 * trống khi tham số lịch không quy định.
 */
public record PeriodMilestones(
    LocalDate planApprovalDeadline,
    LocalDate evidenceDeadline,
    LocalDate acceptanceBacklogDeadline,
    LocalDate plannedLockDate) {

  public static final PeriodMilestones NONE = new PeriodMilestones(null, null, null, null);
}
