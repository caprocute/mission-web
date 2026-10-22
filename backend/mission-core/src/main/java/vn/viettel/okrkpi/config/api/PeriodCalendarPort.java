package vn.viettel.okrkpi.config.api;

import java.time.LocalDate;
import java.util.Optional;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng đọc tham số lịch kỳ từ Policy Pack ({@code plan_approval_deadline_rule} và các mốc, TL-02a
 * "Lịch nghiệp vụ của một quý"). {@code config} là mô-đun gốc, không được gọi {@code policy}
 * (TL-01), nên MOD-08 khai báo cổng này và MOD-05 hiện thực theo nguyên tắc đảo phụ thuộc
 * (CORE-05-03). Chưa có bean hiện thực, hoặc gói chưa khai tham số lịch, thì MOD-08 dùng lịch mặc
 * định của TL-02a.
 */
public interface PeriodCalendarPort {

  /**
   * Lịch của kỳ sắp tạo theo Policy Pack đang hiệu lực của luồng.
   *
   * @param kind loại kỳ ({@code THANG}, {@code QUY}, {@code NAM}, {@code NAM_HOC})
   * @param flow luồng ({@code DANG}, {@code NHA_NUOC}, {@code CA_HAI})
   */
  Optional<PeriodCalendar> calendarFor(
      TenantId tenantId, String kind, String flow, LocalDate startDate, LocalDate endDate);

  /** Các mốc lịch do Policy Pack tính; trường trống dùng giá trị mặc định. */
  record PeriodCalendar(
      LocalDate planApprovalDeadline,
      LocalDate submissionDeadline,
      LocalDate evidenceDeadline,
      LocalDate acceptanceBacklogDeadline,
      LocalDate plannedLockDate) {}
}
