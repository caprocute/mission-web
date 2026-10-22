package vn.viettel.okrkpi.config.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Lịch quốc gia dựng sẵn (scope {@code QUOC_GIA}), dùng khi tenant chưa có dòng {@code QUOC_GIA}
 * trong {@code config.holiday_calendar}. Dữ liệu năm 2026 khớp lịch của bộ ca CEL dùng chung
 * ({@code data/golden/cel/golden-cases.yaml}); các năm khác chưa có dữ liệu, chỉ nghỉ thứ Bảy, Chủ
 * nhật cho tới khi tenant hệ thống nạp lịch (suy luận, cần nghiệp vụ đối chiếu với thông báo nghỉ
 * lễ chính thức hằng năm).
 */
public final class NationalHolidays {

  private static final List<HolidayDay> DAYS =
      List.of(
          day("2026-01-01", HolidayDayKind.NGHI_LE, "Tết Dương lịch"),
          day("2026-02-14", HolidayDayKind.LAM_BU, "Làm bù dịp Tết Nguyên đán"),
          day("2026-02-16", HolidayDayKind.NGHI_LE, "Tết Nguyên đán"),
          day("2026-02-17", HolidayDayKind.NGHI_LE, "Tết Nguyên đán"),
          day("2026-02-18", HolidayDayKind.NGHI_LE, "Tết Nguyên đán"),
          day("2026-02-19", HolidayDayKind.NGHI_LE, "Tết Nguyên đán"),
          day("2026-02-20", HolidayDayKind.NGHI_LE, "Tết Nguyên đán"),
          day("2026-04-25", HolidayDayKind.LAM_BU, "Làm bù dịp Giỗ Tổ Hùng Vương"),
          day("2026-04-27", HolidayDayKind.NGHI_BU, "Nghỉ bù Giỗ Tổ Hùng Vương"),
          day("2026-04-30", HolidayDayKind.NGHI_LE, "Ngày Chiến thắng"),
          day("2026-05-01", HolidayDayKind.NGHI_LE, "Ngày Quốc tế Lao động"),
          day("2026-08-29", HolidayDayKind.LAM_BU, "Làm bù dịp Quốc khánh"),
          day("2026-09-02", HolidayDayKind.NGHI_LE, "Quốc khánh"),
          day("2026-09-03", HolidayDayKind.NGHI_LE, "Quốc khánh"));

  private NationalHolidays() {}

  /** Mọi ngày quốc gia dựng sẵn. */
  public static List<HolidayDay> all() {
    return DAYS;
  }

  /** Các ngày quốc gia dựng sẵn của năm. */
  public static List<HolidayDay> of(int year) {
    return DAYS.stream().filter(d -> d.date().getYear() == year).toList();
  }

  /** Mã dòng tất định của ngày quốc gia dựng sẵn. */
  public static UUID idOf(LocalDate date) {
    return UUID.nameUUIDFromBytes(("QUOC_GIA:" + date).getBytes(StandardCharsets.UTF_8));
  }

  /** Ngày quốc gia dựng sẵn có mã này không. */
  public static boolean isBuiltIn(UUID id) {
    return DAYS.stream().anyMatch(d -> d.id().equals(id));
  }

  private static HolidayDay day(String date, HolidayDayKind kind, String name) {
    LocalDate d = LocalDate.parse(date);
    return new HolidayDay(idOf(d), d, kind, name, HolidayScope.QUOC_GIA, 0);
  }
}
