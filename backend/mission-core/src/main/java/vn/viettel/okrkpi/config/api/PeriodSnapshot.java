package vn.viettel.okrkpi.config.api;

import java.time.LocalDate;
import java.util.Objects;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Ảnh chụp bất biến của kỳ đánh giá được công bố qua cổng {@link PeriodApi} của mô-đun {@code
 * config} ({@code MOD-08}).
 *
 * @param id định danh kỳ đánh giá
 * @param tenantId định danh đơn vị thuê bao (tenant)
 * @param code mã kỳ (ví dụ {@code 2026-Q4})
 * @param name tên hiển thị của kỳ đánh giá
 * @param startDate ngày bắt đầu kỳ
 * @param endDate ngày kết thúc kỳ
 * @param status trạng thái hiện tại của kỳ
 */
public record PeriodSnapshot(
    PeriodId id,
    TenantId tenantId,
    String code,
    String name,
    LocalDate startDate,
    LocalDate endDate,
    PeriodStatus status) {

  public PeriodSnapshot {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(code, "code must not be null");
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(startDate, "startDate must not be null");
    Objects.requireNonNull(endDate, "endDate must not be null");
    Objects.requireNonNull(status, "status must not be null");
  }

  /** Kiểm tra kỳ có đang ở trạng thái mở ({@link PeriodStatus#DANG_HOAT_DONG}) hay không. */
  public boolean isOpen() {
    return status == PeriodStatus.DANG_HOAT_DONG;
  }

  /** Kiểm tra kỳ đã bị khóa ({@link PeriodStatus#DA_KHOA}) hay chưa. */
  public boolean isLocked() {
    return status == PeriodStatus.DA_KHOA;
  }
}
