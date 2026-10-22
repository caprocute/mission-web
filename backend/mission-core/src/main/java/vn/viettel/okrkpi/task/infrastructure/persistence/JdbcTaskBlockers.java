package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.blocker.port.TaskBlockers;

/** {@link TaskBlockers} trên {@code taskDataSource} (vai trò {@code svc_task}, RLS). */
public class JdbcTaskBlockers implements TaskBlockers {

  private static final String COLUMNS =
      "id, code, task_id, period_id, period_bucket, cause_detail, cause_category, cause_claimed,"
          + " impact_level, handler_person_id, resolve_by, status, resolution,"
          + " escalated_to_person_id, created_by, created_at, row_version";

  private final JdbcClient jdbc;

  public JdbcTaskBlockers(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public int nextSeq(TenantId tenant, int periodBucket, UUID taskId) {
    jdbc.sql(
            "SELECT id FROM task.task WHERE period_bucket = ? AND tenant_id = ? AND id = ? FOR"
                + " UPDATE")
        .params(periodBucket, tenant.value(), taskId)
        .query(UUID.class)
        .optional();
    Long n =
        jdbc.sql(
                "SELECT count(*) FROM task.task_blocker WHERE period_bucket = ? AND tenant_id = ?"
                    + " AND task_id = ?")
            .params(periodBucket, tenant.value(), taskId)
            .query(Long.class)
            .single();
    return n.intValue() + 1;
  }

  @Override
  public void insert(TenantId tenant, NewBlocker b, Instant at) {
    Timestamp ts = Timestamp.from(at);
    jdbc.sql(
            "INSERT INTO task.task_blocker (id, created_at, created_by, updated_at, updated_by,"
                + " period_id, period_bucket, task_id, code, cause_category, cause_detail,"
                + " cause_claimed, impact_level, resolve_by, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MO')")
        .params(
            b.id(),
            ts,
            b.reporter(),
            ts,
            b.reporter(),
            b.periodId(),
            b.periodBucket(),
            b.taskId(),
            b.code(),
            b.causeCategory(),
            b.content(),
            b.causeClaimed(),
            b.impactLevel(),
            date(b.resolveBy()))
        .update();
  }

  @Override
  public Optional<BlockerRow> find(TenantId tenant, int periodBucket, UUID taskId, UUID blockerId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM task.task_blocker WHERE period_bucket = ? AND tenant_id = ? AND task_id = ?"
                + " AND id = ?")
        .params(periodBucket, tenant.value(), taskId, blockerId)
        .query((rs, i) -> row(rs))
        .optional();
  }

  @Override
  public boolean change(
      TenantId tenant,
      int periodBucket,
      UUID blockerId,
      long expectedVersion,
      Change c,
      Instant at) {
    return jdbc.sql(
                "UPDATE task.task_blocker SET handler_person_id = ?, resolve_by = ?, status = ?,"
                    + " resolution = ?, resolved_at = coalesce(?, resolved_at),"
                    + " escalated_to_person_id = ?, updated_by = ?, updated_at = ?,"
                    + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ?"
                    + " AND id = ? AND row_version = ?")
            .params(
                c.handlerId(),
                date(c.resolveBy()),
                c.status(),
                c.resolution(),
                c.resolvedAt() == null ? null : Timestamp.from(c.resolvedAt()),
                c.escalatedTo(),
                c.actorId(),
                Timestamp.from(at),
                periodBucket,
                tenant.value(),
                blockerId,
                expectedVersion)
            .update()
        == 1;
  }

  @Override
  public List<BlockerRow> listOf(TenantId tenant, int periodBucket, UUID taskId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM task.task_blocker WHERE period_bucket = ? AND tenant_id = ? AND task_id = ?"
                + " ORDER BY created_at, id")
        .params(periodBucket, tenant.value(), taskId)
        .query((rs, i) -> row(rs))
        .list();
  }

  private static BlockerRow row(ResultSet rs) throws SQLException {
    Date resolveBy = rs.getDate("resolve_by");
    return new BlockerRow(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getObject("task_id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getString("cause_detail"),
        rs.getString("cause_category"),
        rs.getString("cause_claimed"),
        rs.getString("impact_level"),
        rs.getObject("handler_person_id", UUID.class),
        resolveBy == null ? null : resolveBy.toLocalDate(),
        rs.getString("status"),
        rs.getString("resolution"),
        rs.getObject("escalated_to_person_id", UUID.class),
        rs.getObject("created_by", UUID.class),
        rs.getTimestamp("created_at").toInstant(),
        rs.getLong("row_version"));
  }

  private static Date date(LocalDate d) {
    return d == null ? null : Date.valueOf(d);
  }
}
