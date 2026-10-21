package vn.viettel.okrkpi.cel.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Giao diện lịch ngày làm việc dùng cho hàm CEL {@code ngay_lam_viec(tu_ngay, den_ngay)} (và bí
 * danh {@code workdays(from, to)}).
 *
 * <p>Mô-đun {@code MOD-08} ({@code CORE-08-04}) cung cấp bản hiện thực đọc từ bảng {@code
 * config.holiday_calendar}. Thư viện {@code vt-cel} chỉ định nghĩa giao diện này và bản tĩnh {@link
 * StaticWorkingCalendar} phục vụ kiểm thử và đánh giá độc lập.
 *
 * <p>Tham chiếu: NEN-041, BR-06-01, BR-15-03, TL-03 bảng {@code config.holiday_calendar}.
 */
public interface WorkingCalendar {

  /** Phân loại ngày đặc biệt trong {@code config.holiday_calendar.day_kind}. */
  enum DayKind {
    /** Ngày nghỉ lễ chính thức. */
    NGHI_LE,
    /** Ngày nghỉ bù (khi ngày lễ trùng vào cuối tuần). */
    NGHI_BU,
    /** Ngày làm bù (thường rơi vào thứ Bảy/Chủ nhật nhưng được tính là ngày làm việc). */
    LAM_BU
  }

  /**
   * Kiểm tra một ngày dương lịch có phải là ngày làm việc hay không.
   *
   * <p>Quy ước chuẩn: Thứ Hai đến Thứ Sáu là ngày làm việc trừ khi rơi vào {@link DayKind#NGHI_LE}
   * hoặc {@link DayKind#NGHI_BU}; Thứ Bảy và Chủ nhật là ngày nghỉ trừ khi được cấu hình {@link
   * DayKind#LAM_BU}.
   *
   * @param date ngày cần kiểm tra
   * @return {@code true} nếu là ngày làm việc
   */
  boolean isWorkingDay(LocalDate date);

  /**
   * Đếm số ngày làm việc phát sinh trong nửa khoảng {@code (fromExclusive, toInclusive]}.
   *
   * <p>Nếu {@code fromExclusive >= toInclusive} (nộp cùng ngày hoặc sớm hơn mốc bắt đầu), kết quả
   * luôn bằng {@code 0}. Quy ước này khớp tuyệt đối với hàm {@code workdays(from, to)} trên trình
   * duyệt ({@code @vt/form-engine}).
   *
   * @param fromExclusive ngày mốc bắt đầu (không tính ngày này)
   * @param toInclusive ngày mốc kết thúc (có tính nếu là ngày làm việc)
   * @return số ngày làm việc nguyên không âm
   */
  default long countWorkingDays(LocalDate fromExclusive, LocalDate toInclusive) {
    Objects.requireNonNull(fromExclusive, "fromExclusive");
    Objects.requireNonNull(toInclusive, "toInclusive");
    if (!fromExclusive.isBefore(toInclusive)) {
      return 0L;
    }
    long count = 0L;
    for (LocalDate cursor = fromExclusive.plusDays(1);
        !cursor.isAfter(toInclusive);
        cursor = cursor.plusDays(1)) {
      if (isWorkingDay(cursor)) {
        count++;
      }
    }
    return count;
  }

  /**
   * Đếm số ngày làm việc giữa hai chuỗi ngày định dạng ISO-8601 ({@code YYYY-MM-DD}).
   *
   * @param fromIso chuỗi ngày bắt đầu
   * @param toIso chuỗi ngày kết thúc
   * @return số ngày làm việc nguyên không âm
   */
  default long countWorkingDaysIso(String fromIso, String toIso) {
    if (fromIso == null || toIso == null) {
      throw new IllegalArgumentException("Tham số ngày của ngay_lam_viec không được null");
    }
    try {
      LocalDate from = LocalDate.parse(fromIso.trim());
      LocalDate to = LocalDate.parse(toIso.trim());
      return countWorkingDays(from, to);
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException(
          "Định dạng ngày không hợp lệ (cần YYYY-MM-DD): tu='" + fromIso + "', den='" + toIso + "'",
          ex);
    }
  }
}
