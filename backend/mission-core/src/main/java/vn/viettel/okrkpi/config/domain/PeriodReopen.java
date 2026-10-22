package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Một lần mở lại hậu kiểm của kỳ, chiếu từ {@code oversight.reopen_request} qua EVT-22
 * (CORE-08-06): phạm vi, hạn mở và tiến trình khoá lại. {@code id} là mã yêu cầu mở lại.
 */
public record PeriodReopen(
    UUID id,
    TenantId tenantId,
    PeriodId periodId,
    ReopenScope scope,
    Instant openedAt,
    Instant openUntil,
    Instant relockStartedAt,
    Instant relockedAt) {

  public PeriodReopen {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(periodId, "periodId");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(openedAt, "openedAt");
    Objects.requireNonNull(openUntil, "openUntil");
    if (!openUntil.isAfter(openedAt)) {
      throw new IllegalArgumentException("Hạn mở lại phải sau thời điểm mở");
    }
  }

  public static PeriodReopen open(
      UUID id,
      TenantId tenantId,
      PeriodId periodId,
      ReopenScope scope,
      Instant openedAt,
      Instant openUntil) {
    return new PeriodReopen(id, tenantId, periodId, scope, openedAt, openUntil, null, null);
  }

  /** Quá hạn mà chưa khoá lại. */
  public boolean due(Instant now) {
    return relockedAt == null && !now.isBefore(openUntil);
  }
}
