package vn.viettel.okrkpi.task.application.lifecycle.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.domain.lifecycle.DelayForecast;

/**
 * Cột dự báo trễ hạn của {@code task.task} ({@code delay_risk}, {@code delay_risk_detail}, {@code
 * delay_risk_at}). Ghi không đổi {@code row_version} để ETag của người dùng không lệch
 * (CORE-02-26).
 */
public interface TaskDelayRisks {

  /** Thời điểm tính của dự báo đang lưu, rỗng khi chưa có. */
  Optional<Instant> storedAt(TenantId tenant, int periodBucket, UUID taskId);

  Optional<DelayForecast> find(TenantId tenant, int periodBucket, UUID taskId);

  void store(TenantId tenant, int periodBucket, UUID taskId, DelayForecast forecast);
}
