package vn.viettel.okrkpi.task.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.task.domain.lifecycle.RejectionCode;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStateMachine.VersionState;

/** Máy trạng thái phiên bản kế hoạch TL-02a QT-04 và quy tắc thời gian (CORE-02-05). */
class PlanVersionStateMachineTest {

  private static final Map<String, PlanVersionStatus> VALID =
      Map.of(
          "NHAP:SAVE", PlanVersionStatus.NHAP,
          "NHAP:COPY_FROM_PREVIOUS", PlanVersionStatus.NHAP,
          "NHAP:SUBMIT", PlanVersionStatus.CHO_DUYET,
          "TRA_LAI:SAVE", PlanVersionStatus.TRA_LAI,
          "TRA_LAI:COPY_FROM_PREVIOUS", PlanVersionStatus.TRA_LAI,
          "TRA_LAI:SUBMIT", PlanVersionStatus.CHO_DUYET,
          "CHO_DUYET:RETURN", PlanVersionStatus.TRA_LAI,
          "CHO_DUYET:APPROVE", PlanVersionStatus.DA_DUYET);

  @Test
  void moiCapTrangThaiLenh() {
    int accepted = 0;
    for (PlanVersionStatus s : PlanVersionStatus.values()) {
      for (PlanCommand c : PlanCommand.values()) {
        PlanTransition r = PlanVersionStateMachine.apply(s, c, true);
        PlanVersionStatus expected = VALID.get(s + ":" + c);
        if (expected == null) {
          assertThat(r)
              .as("%s:%s", s, c)
              .isInstanceOfSatisfying(
                  PlanTransition.Rejected.class,
                  x -> assertThat(x.code()).isEqualTo(RejectionCode.TRANSITION_NOT_ALLOWED));
        } else {
          accepted++;
          assertThat(r).isEqualTo(new PlanTransition.Accepted(s, expected, c));
        }
        assertThat(PlanVersionStateMachine.apply(s, c, false))
            .isInstanceOfSatisfying(
                PlanTransition.Rejected.class,
                x -> assertThat(x.code()).isEqualTo(RejectionCode.PERIOD_LOCKED));
      }
    }
    assertThat(accepted).isEqualTo(8);
    assertThat(PlanVersionStateMachine.initial()).isEqualTo(PlanVersionStatus.NHAP);
    assertThat(PlanVersionStatus.NHAP.editable()).isTrue();
    assertThat(PlanVersionStatus.TRA_LAI.editable()).isTrue();
    assertThat(PlanVersionStatus.CHO_DUYET.editable()).isFalse();
    assertThat(PlanVersionStatus.DA_DUYET.editable()).isFalse();
    assertThat(
            ((PlanTransition.Rejected)
                    PlanVersionStateMachine.apply(
                        PlanVersionStatus.DA_DUYET, PlanCommand.SAVE, true))
                .message())
        .contains("đã được duyệt");
  }

  @Test
  void phienBanHieuLucLaDaDuyetLonNhat() {
    assertThat(
            PlanVersionStateMachine.effectiveVersion(
                List.of(
                    new VersionState(1, PlanVersionStatus.DA_DUYET),
                    new VersionState(2, PlanVersionStatus.DA_DUYET),
                    new VersionState(3, PlanVersionStatus.NHAP))))
        .contains(2);
    assertThat(
            PlanVersionStateMachine.effectiveVersion(
                List.of(new VersionState(1, PlanVersionStatus.CHO_DUYET))))
        .isEmpty();
  }

  @Test
  void phanVungVaDichHan() {
    assertThat(PlanSchedule.bucketOf(LocalDate.of(2026, 10, 1))).isEqualTo(20264);
    assertThat(PlanSchedule.bucketOf(LocalDate.of(2027, 1, 1))).isEqualTo(20271);
    assertThat(PlanSchedule.previousBucket(20271)).isEqualTo(20264);
    assertThat(PlanSchedule.previousBucket(20263)).isEqualTo(20262);
    LocalDate q4 = LocalDate.of(2026, 10, 1);
    LocalDate q1 = LocalDate.of(2027, 1, 1);
    LocalDate q1End = LocalDate.of(2027, 3, 31);
    assertThat(PlanSchedule.shiftDue(LocalDate.of(2026, 11, 15), q4, q1, q1End))
        .isEqualTo(LocalDate.of(2027, 2, 15));
    // Ngày 31/12 dịch quá cuối quý I (90 ngày) thì kẹp về cuối kỳ.
    assertThat(PlanSchedule.shiftDue(LocalDate.of(2026, 12, 31), q4, q1, q1End)).isEqualTo(q1End);
    assertThat(PlanSchedule.shiftDue(LocalDate.of(2026, 9, 30), q4, q1, q1End)).isEqualTo(q1);
  }
}
