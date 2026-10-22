package vn.viettel.okrkpi.task.domain.mine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class MineDomainTest {

  /** Thứ Hai đến thứ Sáu, trừ ngày lễ 02/09/2026 (thứ Tư). */
  static final Predicate<LocalDate> WORKING =
      d ->
          d.getDayOfWeek() != DayOfWeek.SATURDAY
              && d.getDayOfWeek() != DayOfWeek.SUNDAY
              && !d.equals(LocalDate.of(2026, 9, 2));

  @Test
  void treVaConNgayLamViecQuaCuoiTuanVaNgayLe() {
    LocalDate friday = LocalDate.of(2026, 8, 28);
    LocalDate monday = LocalDate.of(2026, 8, 31);
    LocalDate thursday = LocalDate.of(2026, 9, 3);
    WorkingDays days = WorkingDays.over(friday, thursday, WORKING);
    assertThat(days.between(monday, friday)).isEqualTo(-1);
    assertThat(days.between(friday, monday)).isEqualTo(1);
    // 01/09 thứ Ba, 02/09 nghỉ lễ, 03/09 thứ Năm.
    assertThat(days.between(monday, thursday)).isEqualTo(2);
    assertThat(days.between(monday, monday)).isZero();
    assertThat(days.between(friday, LocalDate.of(2026, 8, 30))).isZero();
    assertThat(days.between(LocalDate.of(2026, 8, 1), friday)).isZero();
    assertThat(WorkingDays.over(thursday, friday, WORKING).between(thursday, thursday)).isZero();
    assertThat(WorkingDays.after(LocalDate.of(2026, 9, 1), 2, WORKING))
        .isEqualTo(LocalDate.of(2026, 9, 4));
    assertThat(WorkingDays.after(monday, 0, WORKING)).isEqualTo(monday);
  }

  @Test
  void boLocNhanh() {
    LocalDate today = LocalDate.of(2026, 9, 1);
    LocalDate soon = LocalDate.of(2026, 9, 4);
    assertThat(QuickFilter.TAT_CA.matches("HUY", false, null, today, soon)).isTrue();
    assertThat(QuickFilter.CHAM.matches("DANG_THUC_HIEN", true, soon, today, soon)).isTrue();
    assertThat(QuickFilter.CHAM.matches("DA_GIAO", false, today.minusDays(1), today, soon))
        .isTrue();
    assertThat(QuickFilter.CHAM.matches("CHO_NGHIEM_THU", true, today, today, soon)).isFalse();
    assertThat(QuickFilter.CHAM.matches("DA_GIAO", false, null, today, soon)).isFalse();
    assertThat(QuickFilter.SAP_HAN.matches("DA_GIAO", false, soon, today, soon)).isTrue();
    assertThat(QuickFilter.SAP_HAN.matches("DA_GIAO", false, soon.plusDays(1), today, soon))
        .isFalse();
    assertThat(QuickFilter.SAP_HAN.matches("DA_GIAO", true, today, today, soon)).isFalse();
    assertThat(QuickFilter.SAP_HAN.matches("DA_GIAO", false, null, today, soon)).isFalse();
    assertThat(QuickFilter.SAP_HAN.matches("HOAN_THANH", false, today, today, soon)).isFalse();
    assertThat(QuickFilter.CHO_NGHIEM_THU.matches("CHO_NGHIEM_THU", false, null, today, soon))
        .isTrue();
    assertThat(QuickFilter.HOAN_THANH.matches("HOAN_THANH", false, null, today, soon)).isTrue();
  }

  @Test
  void khoiLuongTheoTuan() {
    LocalDate due = LocalDate.of(2026, 9, 3);
    List<LocalDate> lead =
        List.of(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 7));
    assertThat(Workload.weekOf(due)).isEqualTo(LocalDate.of(2026, 8, 31));
    assertThat(Workload.inWeekOf(lead, due)).isEqualTo(3);
    Map<LocalDate, Integer> weeks = Workload.byWeek(lead, due.minusWeeks(1), 4);
    assertThat(weeks.values()).containsExactly(0, 2, 1, 0);
    assertThat(Workload.label(LocalDate.of(2026, 8, 31))).isEqualTo("Tuần 36 (31/08–06/09)");
    assertThat(Workload.message("Nguyễn Văn An", 6, due))
        .isEqualTo(
            "Đồng chí Nguyễn Văn An đang chủ trì 6 việc trong tuần 36. Cân nhắc phân công lại.");
    assertThat(Workload.message(" ", 6, due)).startsWith("Người chủ trì được chọn đang chủ trì 6");
    assertThat(Workload.message(null, 6, due)).contains("tuần 36");
  }
}
