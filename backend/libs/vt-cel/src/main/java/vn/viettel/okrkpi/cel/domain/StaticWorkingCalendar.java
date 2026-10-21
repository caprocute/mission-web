package vn.viettel.okrkpi.cel.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bản hiện thực {@link WorkingCalendar} bất biến trong bộ nhớ, dùng cho kiểm thử đơn vị, bộ ca mẫu
 * vàng ({@code data/golden/cel/*.yaml}) và môi trường mặc định khi chưa nạp lịch đơn vị.
 */
public final class StaticWorkingCalendar implements WorkingCalendar {

  private static final StaticWorkingCalendar WEEKENDS_ONLY = new StaticWorkingCalendar(Map.of());

  /**
   * Lịch mẫu cố định dùng chung giữa {@code cel-java} và {@code cel-js} trong bộ ca kiểm thử vàng
   * {@code data/golden/cel/*.yaml}.
   */
  private static final StaticWorkingCalendar DEFAULT_TEST_CALENDAR =
      new StaticWorkingCalendar(
          Map.ofEntries(
              Map.entry(LocalDate.of(2026, 4, 30), DayKind.NGHI_LE),
              Map.entry(LocalDate.of(2026, 5, 1), DayKind.NGHI_LE),
              Map.entry(LocalDate.of(2026, 5, 2), DayKind.LAM_BU),
              Map.entry(LocalDate.of(2026, 9, 2), DayKind.NGHI_LE),
              Map.entry(LocalDate.of(2026, 9, 3), DayKind.NGHI_BU),
              Map.entry(LocalDate.of(2026, 9, 5), DayKind.LAM_BU),
              Map.entry(LocalDate.of(2027, 1, 1), DayKind.NGHI_LE)));

  private final Map<LocalDate, DayKind> specialDays;

  private StaticWorkingCalendar(Map<LocalDate, DayKind> specialDays) {
    this.specialDays = Map.copyOf(Objects.requireNonNull(specialDays, "specialDays"));
  }

  /** Lịch chỉ nghỉ Thứ Bảy và Chủ nhật, không có ngày lễ đặc biệt. */
  public static StaticWorkingCalendar weekendsOnly() {
    return WEEKENDS_ONLY;
  }

  /** Lịch mẫu cố định cho bộ kiểm thử vàng đối chiếu {@code cel-java} và {@code cel-js}. */
  public static StaticWorkingCalendar defaultTestCalendar() {
    return DEFAULT_TEST_CALENDAR;
  }

  /**
   * Tạo lịch từ bản đồ ngày đặc biệt ({@link DayKind#NGHI_LE}, {@link DayKind#NGHI_BU}, {@link
   * DayKind#LAM_BU}).
   */
  public static StaticWorkingCalendar of(Map<LocalDate, DayKind> specialDays) {
    return new StaticWorkingCalendar(specialDays);
  }

  /** Tạo lịch từ tập ngày nghỉ lễ và tập ngày làm bù. */
  public static StaticWorkingCalendar of(
      Set<LocalDate> holidays, Set<LocalDate> compensatoryWorkingDays) {
    Objects.requireNonNull(holidays, "holidays");
    Objects.requireNonNull(compensatoryWorkingDays, "compensatoryWorkingDays");
    Map<LocalDate, DayKind> map = new HashMap<>();
    for (LocalDate d : holidays) {
      map.put(d, DayKind.NGHI_LE);
    }
    for (LocalDate d : compensatoryWorkingDays) {
      map.put(d, DayKind.LAM_BU);
    }
    return new StaticWorkingCalendar(map);
  }

  public Map<LocalDate, DayKind> specialDays() {
    return specialDays;
  }

  @Override
  public boolean isWorkingDay(LocalDate date) {
    Objects.requireNonNull(date, "date");
    DayKind kind = specialDays.get(date);
    if (kind == DayKind.LAM_BU) {
      return true;
    }
    if (kind == DayKind.NGHI_LE || kind == DayKind.NGHI_BU) {
      return false;
    }
    DayOfWeek dayOfWeek = date.getDayOfWeek();
    return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
  }
}
