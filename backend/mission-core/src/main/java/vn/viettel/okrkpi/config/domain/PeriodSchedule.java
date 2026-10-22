package vn.viettel.okrkpi.config.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Lịch nghiệp vụ của một kỳ: hạn duyệt kế hoạch ({@code plan_deadline}), hạn báo cáo kết quả
 * ({@code submission_deadline}) và các mốc.
 */
public record PeriodSchedule(
    LocalDate planDeadline, LocalDate submissionDeadline, PeriodMilestones milestones) {

  public PeriodSchedule {
    milestones = Objects.requireNonNullElse(milestones, PeriodMilestones.NONE);
  }

  /**
   * Lịch sau khi áp giá trị người tạo kỳ nhập: trường nhập thay giá trị tính, trường trống giữ giá
   * trị tính.
   */
  public PeriodSchedule overriddenBy(
      LocalDate plan, LocalDate submission, PeriodMilestones override) {
    PeriodMilestones o = override == null ? PeriodMilestones.NONE : override;
    PeriodMilestones m =
        new PeriodMilestones(
            first(o.planApprovalDeadline(), plan, milestones.planApprovalDeadline()),
            first(o.evidenceDeadline(), milestones.evidenceDeadline()),
            first(o.acceptanceBacklogDeadline(), milestones.acceptanceBacklogDeadline()),
            first(o.plannedLockDate(), milestones.plannedLockDate()));
    LocalDate planDate = first(plan, o.planApprovalDeadline(), planDeadline);
    return new PeriodSchedule(planDate, first(submission, submissionDeadline), m);
  }

  /**
   * BR-08-12: ngày khoá dự kiến phải sớm hơn hạn báo cáo kết quả để còn thời gian đánh giá và báo
   * cáo. Trả {@code true} khi lịch vi phạm (chỉ cảnh báo, không chặn).
   */
  public boolean lockTooLate() {
    LocalDate lock = milestones.plannedLockDate();
    return lock != null && submissionDeadline != null && !lock.isBefore(submissionDeadline);
  }

  private static LocalDate first(LocalDate... values) {
    for (LocalDate v : values) {
      if (v != null) {
        return v;
      }
    }
    return null;
  }
}
