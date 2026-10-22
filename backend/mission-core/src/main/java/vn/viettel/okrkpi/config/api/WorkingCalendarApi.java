package vn.viettel.okrkpi.config.api;

import java.time.LocalDate;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Thư viện tính ngày làm việc dùng chung của MOD-08 (BR-06-01, BR-15-03): lịch quốc gia gộp lịch
 * riêng của tenant ({@code config.holiday_calendar}); thứ Bảy, Chủ nhật nghỉ trừ ngày {@code
 * LAM_BU}, ngày thường làm việc trừ {@code NGHI_LE}, {@code NGHI_BU}. Mọi mô-đun tính hạn duyệt kế
 * hoạch, hạn nghiệm thu, cờ quá hạn, thời hạn mở lại kỳ hay timer quy trình đều gọi cổng này để có
 * cùng kết quả với hàm CEL {@code ngay_lam_viec}.
 */
public interface WorkingCalendarApi {

  boolean isWorkingDay(TenantId tenantId, LocalDate date);

  /**
   * Ngày làm việc thứ {@code n} sau {@code from} ({@code n} âm thì đếm lùi; {@code n = 0} trả
   * nguyên {@code from}).
   */
  LocalDate addWorkingDays(TenantId tenantId, LocalDate from, int n);

  /**
   * Số ngày làm việc trong nửa khoảng {@code (fromExclusive, toInclusive]}, bằng 0 khi {@code
   * fromExclusive >= toInclusive} (khớp {@code ngay_lam_viec(tu_ngay, den_ngay)}).
   */
  long countWorkingDays(TenantId tenantId, LocalDate fromExclusive, LocalDate toInclusive);

  /** Lịch của tenant dưới dạng giao diện {@code WorkingCalendar} của {@code vt-cel}. */
  vn.viettel.okrkpi.cel.domain.WorkingCalendar celCalendar(TenantId tenantId);
}
