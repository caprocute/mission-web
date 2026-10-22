package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskProgressRepository;

/** {@link TaskProgressRepository} trên {@code taskDataSource} (vai trò {@code svc_task}, RLS). */
public class JdbcTaskProgressRepository implements TaskProgressRepository {

  private final JdbcClient jdbc;

  public JdbcTaskProgressRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public boolean exists(TenantId tenant, int periodBucket, String idempotencyKey) {
    return jdbc.sql(
                "SELECT count(*) FROM task.task_progress WHERE period_bucket = ? AND tenant_id = ?"
                    + " AND idempotency_key = ?")
            .params(periodBucket, tenant.value(), idempotencyKey)
            .query(Long.class)
            .single()
        > 0;
  }

  @Override
  public void insert(TenantId tenant, NewProgress p) {
    jdbc.sql(
            "INSERT INTO task.task_progress (id, created_by, updated_by, period_id, period_bucket,"
                + " task_id, reported_at, progress_pct, done_quantity, note, channel,"
                + " idempotency_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            p.id(),
            p.actorId(),
            p.actorId(),
            p.periodId(),
            p.periodBucket(),
            p.taskId(),
            Timestamp.from(p.reportedAt()),
            p.ratio(),
            p.doneQuantity(),
            p.note(),
            p.channel(),
            p.idempotencyKey())
        .update();
  }

  @Override
  public long applyProgress(
      TenantId tenant,
      int periodBucket,
      UUID taskId,
      long expectedVersion,
      BigDecimal ratio,
      BigDecimal doneQuantity,
      UUID actorId,
      Instant at) {
    int n =
        jdbc.sql(
                "UPDATE task.task SET progress_pct = ?, done_quantity = coalesce(?, done_quantity),"
                    + " updated_by = ?, updated_at = ?, row_version = row_version + 1"
                    + " WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND row_version = ?")
            .params(
                ratio,
                doneQuantity,
                actorId,
                Timestamp.from(at),
                periodBucket,
                tenant.value(),
                taskId,
                expectedVersion)
            .update();
    return n == 1 ? expectedVersion + 1 : -1;
  }
}
