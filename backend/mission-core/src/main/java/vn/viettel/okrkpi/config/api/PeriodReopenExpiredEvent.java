package vn.viettel.okrkpi.config.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kỳ mở lại hậu kiểm đã quá hạn mà chưa hoàn tất (ngoại lệ QT-13): MOD-07 nghe sự kiện này để đánh
 * dấu các hồ sơ trong phạm vi là "chưa hoàn tất hậu kiểm" (CORE-08-06). MOD-08 không gọi trực tiếp
 * MOD-07.
 */
public record PeriodReopenExpiredEvent(
    TenantId tenantId, PeriodId periodId, UUID reopenRequestId, Instant openUntil) {
  public PeriodReopenExpiredEvent {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(periodId, "periodId");
    Objects.requireNonNull(reopenRequestId, "reopenRequestId");
    Objects.requireNonNull(openUntil, "openUntil");
  }
}
