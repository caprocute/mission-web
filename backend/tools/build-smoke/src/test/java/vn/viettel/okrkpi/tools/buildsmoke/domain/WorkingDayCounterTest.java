package vn.viettel.okrkpi.tools.buildsmoke.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WorkingDayCounterTest {

  @Test
  void demNamNgayLamViecTrongMotTuan() {
    assertEquals(5, WorkingDayCounter.count(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11)));
  }

  @Test
  void motNgayThuBayKhongPhaiNgayLamViec() {
    assertEquals(
        0, WorkingDayCounter.count(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 10)));
    assertFalse(WorkingDayCounter.isWorkingDay(LocalDate.of(2026, 10, 11)));
    assertTrue(WorkingDayCounter.isWorkingDay(LocalDate.of(2026, 10, 9)));
  }

  @Test
  void tinhCaHaiDauDoan() {
    assertEquals(1, WorkingDayCounter.count(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 9)));
  }

  @Test
  void tuChoiKhoangNguoc() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkingDayCounter.count(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 8)));
  }
}
