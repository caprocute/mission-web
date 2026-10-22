package vn.viettel.okrkpi.config.domain;

/** Loại ngày đặc biệt của {@code config.holiday_calendar.day_kind} (BR-06-01). */
public enum HolidayDayKind {
  /** Nghỉ lễ, Tết. */
  NGHI_LE,
  /** Nghỉ bù khi ngày lễ trùng cuối tuần hoặc do hoán đổi. */
  NGHI_BU,
  /** Làm bù vào thứ Bảy hoặc Chủ nhật. */
  LAM_BU;

  /** Ngày loại này có phải ngày làm việc không. */
  public boolean working() {
    return this == LAM_BU;
  }
}
