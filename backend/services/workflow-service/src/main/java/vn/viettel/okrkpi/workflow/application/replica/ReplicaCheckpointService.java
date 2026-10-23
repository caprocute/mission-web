package vn.viettel.okrkpi.workflow.application.replica;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.workflow.application.port.ReplicaCheckpointStore;
import vn.viettel.okrkpi.workflow.domain.replica.ReplicaEntityType;

/**
 * Tính và ghi độ trễ áp dụng của bản sao tổ chức (TL-03 {@code org_replica.replica_checkpoint}, số
 * đo {@code org_replica_lag_ms}) theo mẫu {@code JdbcOrgReplicaStore.checkpoint} của mission-core:
 * độ trễ là khoảng từ thời điểm phát của sự kiện tới lúc consumer áp, không âm. Chạy trong giao
 * dịch của consumer inbox, nên mốc commit hoặc huỷ cùng bản sao.
 */
@Service
public class ReplicaCheckpointService {

  private final ReplicaCheckpointStore store;
  private final Clock clock;

  public ReplicaCheckpointService(ReplicaCheckpointStore store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Ghi mốc của một sự kiện đã qua consumer.
   *
   * @param occurredAt thời điểm phát; {@code null} (sự kiện thiếu header) coi như vừa phát
   * @return độ trễ (ms)
   */
  public long record(UUID tenantId, ReplicaEntityType type, UUID eventId, Instant occurredAt) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(eventId, "eventId");
    Instant now = clock.instant();
    Instant at = occurredAt == null ? now : occurredAt;
    long lag = Math.max(0, Duration.between(at, now).toMillis());
    store.record(tenantId, type, eventId, at, now, lag);
    return lag;
  }
}
