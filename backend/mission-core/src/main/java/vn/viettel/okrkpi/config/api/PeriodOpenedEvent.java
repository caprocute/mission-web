package vn.viettel.okrkpi.config.api;

import java.time.Instant;
import java.util.Objects;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Sự kiện nội bộ phát ra từ {@code MOD-08 (config)} khi một kỳ đánh giá được mở chính thức, được
 * ghi nhận qua Spring Modulith Event Publication Registry ({@code modulith.event_publication}) để
 * các mô-đun phụ thuộc (như {@code MOD-01 okr}) xử lý bất đồng bộ có bảo toàn giao dịch.
 *
 * @param periodId định danh kỳ đánh giá
 * @param tenantId định danh đơn vị thuê bao
 * @param code mã kỳ đánh giá
 * @param openedAt thời điểm mở kỳ (UTC)
 */
public record PeriodOpenedEvent(
    PeriodId periodId, TenantId tenantId, String code, Instant openedAt) {

  public PeriodOpenedEvent {
    Objects.requireNonNull(periodId, "periodId must not be null");
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(code, "code must not be null");
    Objects.requireNonNull(openedAt, "openedAt must not be null");
  }
}
