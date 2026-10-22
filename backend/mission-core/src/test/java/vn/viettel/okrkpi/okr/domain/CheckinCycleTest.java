package vn.viettel.okrkpi.okr.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Chu kỳ check-in và ngày nhắc của CORE-01-11. */
class CheckinCycleTest {

  static final LocalDate PERIOD_START = LocalDate.of(2027, 1, 1); // thứ Sáu

  @Test
  void tuanLaThuHaiDenChuNhatVaTuanNaoCungNhac() {
    CheckinCycle c = CheckinCycle.of("TUAN", LocalDate.of(2027, 2, 11), PERIOD_START);
    assertThat(c).isEqualTo(new CheckinCycle(LocalDate.of(2027, 2, 8), LocalDate.of(2027, 2, 14)));
    assertThat(CheckinCycle.remindWeek("TUAN", LocalDate.of(2027, 2, 11), PERIOD_START)).isTrue();
    assertThat(c.previous("TUAN", PERIOD_START).start()).isEqualTo(LocalDate.of(2027, 2, 1));
  }

  @Test
  void haiTuanTinhTuThuHaiCuaTuanMoKyVaNhacTuanThuHai() {
    // Tuần mở kỳ bắt đầu thứ Hai 28/12/2026.
    CheckinCycle c = CheckinCycle.of("HAI_TUAN", LocalDate.of(2027, 1, 7), PERIOD_START);
    assertThat(c)
        .isEqualTo(new CheckinCycle(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 10)));
    assertThat(CheckinCycle.remindWeek("HAI_TUAN", LocalDate.of(2026, 12, 31), PERIOD_START))
        .isFalse();
    assertThat(CheckinCycle.remindWeek("HAI_TUAN", LocalDate.of(2027, 1, 7), PERIOD_START))
        .isTrue();
    assertThat(CheckinCycle.of("HAI_TUAN", LocalDate.of(2027, 1, 14), PERIOD_START).start())
        .isEqualTo(LocalDate.of(2027, 1, 11));
  }

  @Test
  void thangChiNhacTuanCoThuNamCuoiThang() {
    assertThat(CheckinCycle.remindWeek("THANG", LocalDate.of(2027, 2, 11), PERIOD_START)).isFalse();
    assertThat(CheckinCycle.remindWeek("THANG", LocalDate.of(2027, 2, 18), PERIOD_START)).isFalse();
    assertThat(CheckinCycle.remindWeek("THANG", LocalDate.of(2027, 2, 25), PERIOD_START)).isTrue();
    // Tháng 3/2027 kết thúc thứ Tư 31/3: thứ Năm cuối là 25/3.
    assertThat(CheckinCycle.remindWeek("THANG", LocalDate.of(2027, 3, 25), PERIOD_START)).isTrue();
    CheckinCycle feb = CheckinCycle.of("THANG", LocalDate.of(2027, 2, 25), PERIOD_START);
    assertThat(feb.previous("THANG", PERIOD_START))
        .isEqualTo(new CheckinCycle(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)));
  }

  @Test
  void thuNamNghiThiDoiSangNgayLamViecKeTiep() {
    LocalDate monday = LocalDate.of(2027, 2, 8);
    assertThat(CheckinCycle.reminderDay(monday, d -> true)).isEqualTo(LocalDate.of(2027, 2, 11));
    Set<LocalDate> off = Set.of(LocalDate.of(2027, 2, 11), LocalDate.of(2027, 2, 12));
    assertThat(CheckinCycle.reminderDay(monday, d -> !off.contains(d)))
        .isEqualTo(LocalDate.of(2027, 2, 13));
    assertThat(CheckinCycle.reminderDay(monday, d -> false)).isNull();
  }
}
