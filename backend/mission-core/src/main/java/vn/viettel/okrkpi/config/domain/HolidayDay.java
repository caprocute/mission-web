package vn.viettel.okrkpi.config.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Một ngày của lịch nghỉ, làm bù ({@code config.holiday_calendar}). Ngày lịch quốc gia dựng sẵn
 * trong mã ({@link NationalHolidays}) có mã dòng tất định và {@code rowVersion = 0}.
 */
public record HolidayDay(
    UUID id, LocalDate date, HolidayDayKind kind, String name, HolidayScope scope, int rowVersion) {

  /** Độ dài tối đa của tên ngày ({@code varchar(100)}). */
  public static final int NAME_MAX = 100;

  public HolidayDay {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(date, "date");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(scope, "scope");
  }

  /**
   * Lỗi dữ liệu nhập theo BR-06-01, {@code null} nếu hợp lệ: tên không trống và không quá 100 ký
   * tự; năm trong 2020..2100; ngày làm bù phải là thứ Bảy hoặc Chủ nhật (suy luận).
   */
  public static String validate(LocalDate date, HolidayDayKind kind, String name) {
    if (date == null || kind == null || name == null || name.isBlank()) {
      return "Cần nhập ngày, loại ngày và tên ngày.";
    }
    if (name.strip().length() > NAME_MAX) {
      return "Tên ngày không quá 100 ký tự.";
    }
    if (date.getYear() < 2020 || date.getYear() > 2100) {
      return "Năm phải trong khoảng 2020 đến 2100.";
    }
    if (kind == HolidayDayKind.LAM_BU && !weekend(date)) {
      return "Ngày làm bù phải là thứ Bảy hoặc Chủ nhật.";
    }
    return null;
  }

  static boolean weekend(LocalDate d) {
    DayOfWeek w = d.getDayOfWeek();
    return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
  }
}
