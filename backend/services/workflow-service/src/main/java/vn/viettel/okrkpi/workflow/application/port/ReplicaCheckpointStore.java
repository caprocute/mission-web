package vn.viettel.okrkpi.workflow.application.port;

import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.replica.ReplicaEntityType;

/**
 * Cổng ghi {@code org_replica.replica_checkpoint} của workflow-service (TL-03 "Bảng
 * org_replica.replica_checkpoint"): sự kiện áp gần nhất và độ trễ áp dụng theo tenant và loại thực
 * thể. Chạy trong giao dịch của consumer inbox, đã đặt ngữ cảnh tenant.
 */
public interface ReplicaCheckpointStore {

  /**
   * Ghi mốc mới; không lùi mốc khi sự kiện đến muộn có thời điểm phát cũ hơn mốc đang có.
   *
   * @param lagMs độ trễ từ lúc phát tới lúc áp (ms, không âm)
   */
  void record(
      UUID tenantId,
      ReplicaEntityType type,
      UUID eventId,
      Instant occurredAt,
      Instant appliedAt,
      long lagMs);
}
