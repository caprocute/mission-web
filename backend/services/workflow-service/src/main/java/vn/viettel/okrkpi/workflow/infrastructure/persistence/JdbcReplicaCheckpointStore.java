package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.workflow.application.port.ReplicaCheckpointStore;
import vn.viettel.okrkpi.workflow.domain.replica.ReplicaEntityType;

/**
 * {@link ReplicaCheckpointStore} trên {@code org_replica.replica_checkpoint} (TL-03, tạo ở V14),
 * cùng câu upsert với mission-core: mốc chỉ tiến khi thời điểm phát không cũ hơn mốc đang có.
 */
@Repository
public class JdbcReplicaCheckpointStore implements ReplicaCheckpointStore {

  static final String UPSERT =
      """
      INSERT INTO org_replica.replica_checkpoint AS c (tenant_id, entity_type, last_event_id,
        last_occurred_at, last_applied_at, lag_ms)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (tenant_id, entity_type) DO UPDATE SET
        last_event_id = excluded.last_event_id, last_occurred_at = excluded.last_occurred_at,
        last_applied_at = excluded.last_applied_at, lag_ms = excluded.lag_ms
      WHERE excluded.last_occurred_at >= c.last_occurred_at
      """;

  private final JdbcTemplate jdbc;

  public JdbcReplicaCheckpointStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void record(
      UUID tenantId,
      ReplicaEntityType type,
      UUID eventId,
      Instant occurredAt,
      Instant appliedAt,
      long lagMs) {
    TenantBinding.bind(jdbc, tenantId);
    jdbc.update(
        UPSERT,
        tenantId,
        type.name(),
        eventId,
        Timestamp.from(occurredAt),
        Timestamp.from(appliedAt),
        (int) Math.min(Integer.MAX_VALUE, Math.max(0, lagMs)));
  }
}
