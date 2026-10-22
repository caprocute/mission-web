package vn.viettel.okrkpi.config.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Lịch mặc định so với ví dụ của TL-02a "Lịch nghiệp vụ của một quý" và hợp đồng. */
class DefaultPeriodCalendarTest {

  @Test
  void quarterOneOf2027MatchesTl02aExample() {
    PeriodSchedule s =
        DefaultPeriodCalendar.scheduleOf(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31));
    assertThat(s.planDeadline()).isEqualTo(LocalDate.of(2027, 1, 8));
    assertThat(s.milestones().planApprovalDeadline()).isEqualTo(LocalDate.of(2027, 1, 8));
    assertThat(s.milestones().evidenceDeadline()).isEqualTo(LocalDate.of(2027, 3, 31));
    assertThat(s.milestones().acceptanceBacklogDeadline()).isEqualTo(LocalDate.of(2027, 4, 2));
    assertThat(s.milestones().plannedLockDate()).isEqualTo(LocalDate.of(2027, 4, 5));
    assertThat(s.submissionDeadline()).isEqualTo(LocalDate.of(2027, 4, 9));
    assertThat(s.lockTooLate()).isFalse();
  }

  @Test
  void quarterFourOf2026MatchesPlanAndSubmissionDeadlines() {
    PeriodSchedule s =
        DefaultPeriodCalendar.scheduleOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31));
    assertThat(s.planDeadline()).isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(s.submissionDeadline()).isEqualTo(LocalDate.of(2027, 1, 8));
  }

  @Test
  void overrideKeepsComputedValuesForBlankFieldsAndFlagsLateLock() {
    PeriodSchedule s =
        DefaultPeriodCalendar.scheduleOf(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31))
            .overriddenBy(
                LocalDate.of(2027, 1, 7),
                null,
                new PeriodMilestones(null, null, null, LocalDate.of(2027, 4, 9)));
    assertThat(s.planDeadline()).isEqualTo(LocalDate.of(2027, 1, 7));
    assertThat(s.milestones().planApprovalDeadline()).isEqualTo(LocalDate.of(2027, 1, 7));
    assertThat(s.milestones().acceptanceBacklogDeadline()).isEqualTo(LocalDate.of(2027, 4, 2));
    assertThat(s.lockTooLate()).isTrue();
    assertThat(new PeriodSchedule(null, null, null).milestones()).isEqualTo(PeriodMilestones.NONE);
    assertThat(new PeriodSchedule(null, null, null).lockTooLate()).isFalse();
  }

  @Test
  void planDeadlineFallsBackToFridayWhenSecondWeekEndsOnWeekend() {
    // Tháng 2/2027 bắt đầu thứ Hai: tuần 1 là 01-07/02, tuần 2 kết thúc Chủ nhật 14/02.
    assertThat(DefaultPeriodCalendar.planApprovalDeadline(LocalDate.of(2027, 2, 1)))
        .isEqualTo(LocalDate.of(2027, 2, 12));
    assertThat(DefaultPeriodCalendar.workingDay(LocalDate.of(2027, 2, 13))).isFalse();
    assertThat(DefaultPeriodCalendar.addWorkingDays(LocalDate.of(2027, 2, 12), 1))
        .isEqualTo(LocalDate.of(2027, 2, 15));
  }
}
