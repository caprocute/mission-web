package vn.viettel.okrkpi.config.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Sự kiện ứng dụng nội bộ "kỳ đã tạo" của MOD-08, phát qua Spring Modulith Event Publication
 * Registry để MOD-05 ghim phiên bản Policy Pack đang hiệu lực vào {@code policy.policy_binding} cho
 * kỳ (BR-08-07). Không phải sự kiện liên dịch vụ nên không mang mã EVT (wiki trang 14).
 */
public record PeriodCreatedEvent(
    PeriodId periodId,
    TenantId tenantId,
    String code,
    String kind,
    String flow,
    LocalDate startDate,
    LocalDate endDate,
    int periodBucket,
    Instant createdAt) {

  public PeriodCreatedEvent {
    Objects.requireNonNull(periodId, "periodId");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(flow, "flow");
    Objects.requireNonNull(startDate, "startDate");
    Objects.requireNonNull(endDate, "endDate");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
