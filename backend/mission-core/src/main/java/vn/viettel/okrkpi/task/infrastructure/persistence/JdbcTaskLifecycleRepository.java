package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

/** {@link TaskLifecycleRepository} trên {@code taskDataSource} (vai trò {@code svc_task}, RLS). */
public class JdbcTaskLifecycleRepository implements TaskLifecycleRepository {

  private static final String COLUMNS =
      "id, period_id, period_bucket, code, title, description, task_group, source, key_result_id,"
          + " assigner_id, assigned_at, acceptor_id, due_date, extended_due_date, score_due_date,"
          + " planned_quantity, done_quantity, unit, difficulty, standard_score, progress_pct,"
          + " status, is_overdue, delay_risk, state_machine_version, row_version";

  private final JdbcClient jdbc;

  public JdbcTaskLifecycleRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<TaskRow> find(TenantId tenant, UUID taskId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM task.task WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), taskId)
        .query(JdbcTaskLifecycleRepository::task)
        .optional();
  }

  @Override
  public Optional<TaskRow> lock(TenantId tenant, UUID taskId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM task.task WHERE tenant_id = ? AND id = ? FOR UPDATE")
        .params(tenant.value(), taskId)
        .query(JdbcTaskLifecycleRepository::task)
        .optional();
  }

  @Override
  public Optional<UUID> leadAssignee(TenantId tenant, int periodBucket, UUID taskId) {
    return jdbc.sql(
            "SELECT person_id FROM task.task_assignee WHERE period_bucket = ? AND tenant_id = ?"
                + " AND task_id = ? AND role = 'CHU_TRI' AND removed_at IS NULL"
                + " AND recorded_to = 'infinity' AND valid_to = 'infinity'"
                + " AND person_id IS NOT NULL ORDER BY valid_from DESC LIMIT 1")
        .params(periodBucket, tenant.value(), taskId)
        .query(UUID.class)
        .optional();
  }

  @Override
  public Optional<ChangeRequestRow> latestChangeRequest(
      TenantId tenant, int periodBucket, UUID taskId) {
    return jdbc.sql(
            "SELECT request_type, status FROM task.task_change_request WHERE period_bucket = ?"
                + " AND tenant_id = ? AND task_id = ? AND request_type IN ('TAM_DUNG', 'HUY')"
                + " ORDER BY coalesce(decided_at, created_at) DESC, id DESC LIMIT 1")
        .params(periodBucket, tenant.value(), taskId)
        .query((rs, i) -> new ChangeRequestRow(rs.getString(1), rs.getString(2)))
        .optional();
  }

  @Override
  public long changeStatus(
      TenantId tenant, int periodBucket, UUID taskId, long expectedVersion, StatusUpdate u) {
    int n =
        jdbc.sql(
                "UPDATE task.task SET status = ?, state_machine_version = ?, updated_by = ?,"
                    + " updated_at = ?, row_version = row_version + 1,"
                    + " completed_at = coalesce(?, completed_at),"
                    + " cancelled_reason = coalesce(?, cancelled_reason)"
                    + " WHERE period_bucket = ? AND tenant_id = ? AND id = ? AND row_version = ?")
            .params(
                u.to().name(),
                u.stateMachineVersion(),
                u.actorId(),
                Timestamp.from(u.at()),
                u.completedAt() == null ? null : Timestamp.from(u.completedAt()),
                u.cancelledReason(),
                periodBucket,
                tenant.value(),
                taskId,
                expectedVersion)
            .update();
    if (n != 1) {
      throw new NoSuchElementException("Nhiệm vụ đã bị thay đổi hoặc không còn");
    }
    return expectedVersion + 1;
  }

  @Override
  public void appendStateLog(
      TenantId tenant,
      UUID periodId,
      int periodBucket,
      UUID taskId,
      StateLogRow row,
      String beforeAfterJson) {
    jdbc.sql(
            "INSERT INTO task.task_state_log (id, period_id, period_bucket, task_id, from_status,"
                + " to_status, transition, actor_id, reason, before_after, state_machine_version,"
                + " occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)")
        .params(
            UUID.randomUUID(),
            periodId,
            periodBucket,
            taskId,
            row.from() == null ? null : row.from().name(),
            row.to().name(),
            row.transition(),
            row.actorId(),
            row.reason(),
            beforeAfterJson,
            row.stateMachineVersion(),
            Timestamp.from(row.occurredAt()))
        .update();
  }

  @Override
  public List<StateLogRow> stateLog(TenantId tenant, int periodBucket, UUID taskId) {
    return jdbc.sql(
            "SELECT occurred_at, from_status, to_status, transition, actor_id, reason,"
                + " state_machine_version FROM task.task_state_log WHERE period_bucket = ?"
                + " AND tenant_id = ? AND task_id = ? ORDER BY occurred_at, id")
        .params(periodBucket, tenant.value(), taskId)
        .query(
            (rs, i) ->
                new StateLogRow(
                    instant(rs, "occurred_at"),
                    status(rs.getString("from_status")),
                    status(rs.getString("to_status")),
                    rs.getString("transition"),
                    rs.getObject("actor_id", UUID.class),
                    rs.getString("reason"),
                    rs.getString("state_machine_version")))
        .list();
  }

  private static TaskRow task(ResultSet rs, int i) throws SQLException {
    int difficulty = rs.getInt("difficulty");
    boolean noDifficulty = rs.wasNull();
    return new TaskRow(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getString("code"),
        rs.getString("title"),
        rs.getString("description"),
        rs.getString("task_group"),
        rs.getString("source"),
        rs.getObject("key_result_id", UUID.class),
        rs.getObject("assigner_id", UUID.class),
        instant(rs, "assigned_at"),
        rs.getObject("acceptor_id", UUID.class),
        rs.getObject("due_date", LocalDate.class),
        rs.getObject("extended_due_date", LocalDate.class),
        rs.getObject("score_due_date", LocalDate.class),
        rs.getBigDecimal("planned_quantity"),
        rs.getBigDecimal("done_quantity"),
        rs.getString("unit"),
        noDifficulty ? null : difficulty,
        rs.getBigDecimal("standard_score"),
        rs.getBigDecimal("progress_pct"),
        TaskStatus.valueOf(rs.getString("status")),
        rs.getBoolean("is_overdue"),
        rs.getString("delay_risk"),
        rs.getString("state_machine_version"),
        rs.getLong("row_version"));
  }

  private static TaskStatus status(String s) {
    return s == null ? null : TaskStatus.valueOf(s);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }
}
