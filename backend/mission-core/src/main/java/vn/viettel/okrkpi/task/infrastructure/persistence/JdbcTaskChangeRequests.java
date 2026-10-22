package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.change.port.TaskChangeRequests;

/** {@link TaskChangeRequests} trên {@code taskDataSource} (vai trò {@code svc_task}, RLS). */
public class JdbcTaskChangeRequests implements TaskChangeRequests {

  private static final String COLUMNS =
      "id, task_id, request_type, status, proposed_due_date, proposed_quantity,"
          + " new_values ->> 'newProduct' AS new_product, cause_claimed, blocker_id, reason,"
          + " approver_id, created_by, created_at, decided_at, decided_by, cause_decided, decision_note,"
          + " coalesce((new_values ->> 'overThreshold')::boolean, false) AS escalated, row_version";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcTaskChangeRequests(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void lockTask(TenantId tenant, int periodBucket, UUID taskId) {
    jdbc.sql(
            "SELECT id FROM task.task WHERE period_bucket = ? AND tenant_id = ? AND id = ? FOR"
                + " UPDATE")
        .params(periodBucket, tenant.value(), taskId)
        .query(UUID.class)
        .optional();
  }

  @Override
  public boolean hasPending(TenantId tenant, int periodBucket, UUID taskId, String type) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM task.task_change_request WHERE period_bucket = ?"
                + " AND tenant_id = ? AND task_id = ? AND request_type = ? AND status = 'CHO_DUYET')")
        .params(periodBucket, tenant.value(), taskId, type)
        .query(Boolean.class)
        .single();
  }

  @Override
  public int approvedExtensions(TenantId tenant, int periodBucket, UUID taskId) {
    return jdbc.sql(
            "SELECT count(*) FROM task.task_change_request WHERE period_bucket = ? AND tenant_id = ?"
                + " AND task_id = ? AND request_type = 'GIA_HAN' AND status = 'DA_DUYET'")
        .params(periodBucket, tenant.value(), taskId)
        .query(Long.class)
        .single()
        .intValue();
  }

  @Override
  public boolean insert(TenantId tenant, NewChange c, Instant at) {
    Timestamp ts = Timestamp.from(at);
    return jdbc.sql(
                "INSERT INTO task.task_change_request (id, created_at, created_by, updated_at,"
                    + " updated_by, period_id, period_bucket, task_id, request_type,"
                    + " proposed_due_date, proposed_quantity, reason, is_objective_reason, status,"
                    + " cause_claimed, blocker_id, approver_id, new_values) VALUES (?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?, 'CHO_DUYET', ?, ?, ?, ?::jsonb) ON CONFLICT DO"
                    + " NOTHING")
            .params(
                c.id(),
                ts,
                c.actorId(),
                ts,
                c.actorId(),
                c.periodId(),
                c.periodBucket(),
                c.taskId(),
                c.type(),
                c.proposedDueDate() == null ? null : Date.valueOf(c.proposedDueDate()),
                c.proposedQuantity(),
                c.reason(),
                "KHACH_QUAN".equals(c.causeClaimed()),
                c.causeClaimed(),
                c.blockerId(),
                c.approverId(),
                write(c))
            .update()
        == 1;
  }

  @Override
  public Optional<ChangeRow> find(TenantId tenant, int periodBucket, UUID taskId, UUID requestId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM task.task_change_request WHERE period_bucket = ? AND tenant_id = ?"
                + " AND task_id = ? AND id = ?")
        .params(periodBucket, tenant.value(), taskId, requestId)
        .query((rs, i) -> row(rs))
        .optional();
  }

  @Override
  public boolean withdraw(
      TenantId tenant, int periodBucket, UUID requestId, UUID actorId, Instant at) {
    return jdbc.sql(
                "UPDATE task.task_change_request SET status = 'DA_RUT', updated_by = ?,"
                    + " updated_at = ?, row_version = row_version + 1 WHERE period_bucket = ?"
                    + " AND tenant_id = ? AND id = ? AND status = 'CHO_DUYET'")
            .params(actorId, Timestamp.from(at), periodBucket, tenant.value(), requestId)
            .update()
        == 1;
  }

  @Override
  public List<ChangeRow> listOf(TenantId tenant, int periodBucket, UUID taskId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM task.task_change_request WHERE period_bucket = ? AND tenant_id = ?"
                + " AND task_id = ? AND status <> 'NHAP' ORDER BY created_at DESC, id")
        .params(periodBucket, tenant.value(), taskId)
        .query((rs, i) -> row(rs))
        .list();
  }

  @Override
  public boolean decide(
      TenantId tenant,
      int periodBucket,
      UUID requestId,
      String status,
      String causeDecided,
      String note,
      UUID actorId,
      Instant at) {
    Timestamp ts = Timestamp.from(at);
    return jdbc.sql(
                "UPDATE task.task_change_request SET status = ?, cause_decided = ?,"
                    + " decision_note = ?, decided_by = ?, decided_at = ?, updated_by = ?,"
                    + " updated_at = ?, row_version = row_version + 1 WHERE period_bucket = ?"
                    + " AND tenant_id = ? AND id = ? AND status = 'CHO_DUYET'")
            .params(
                status,
                causeDecided,
                note,
                actorId,
                ts,
                actorId,
                ts,
                periodBucket,
                tenant.value(),
                requestId)
            .update()
        == 1;
  }

  @Override
  public long applyToTask(
      TenantId tenant, int periodBucket, UUID taskId, long expectedVersion, TaskChange c) {
    int n =
        jdbc.sql(
                "UPDATE task.task SET extended_due_date = coalesce(?, extended_due_date),"
                    + " score_due_date = coalesce(?, score_due_date),"
                    + " planned_quantity = coalesce(?, planned_quantity),"
                    + " product_name = coalesce(?, product_name), updated_by = ?, updated_at = ?,"
                    + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ?"
                    + " AND id = ? AND row_version = ?")
            .params(
                c.extendedDueDate() == null ? null : Date.valueOf(c.extendedDueDate()),
                c.scoreDueDate() == null ? null : Date.valueOf(c.scoreDueDate()),
                c.plannedQuantity(),
                c.productName(),
                c.actorId(),
                Timestamp.from(c.at()),
                periodBucket,
                tenant.value(),
                taskId,
                expectedVersion)
            .update();
    return n == 1 ? expectedVersion + 1 : -1;
  }

  private String write(NewChange c) {
    try {
      return json.writeValueAsString(c.newValues());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ChangeRow row(ResultSet rs) throws SQLException {
    Date due = rs.getDate("proposed_due_date");
    Timestamp decided = rs.getTimestamp("decided_at");
    return new ChangeRow(
        rs.getObject("id", UUID.class),
        rs.getObject("task_id", UUID.class),
        rs.getString("request_type"),
        rs.getString("status"),
        due == null ? null : due.toLocalDate(),
        rs.getBigDecimal("proposed_quantity"),
        rs.getString("new_product"),
        rs.getString("cause_claimed"),
        rs.getObject("blocker_id", UUID.class),
        rs.getString("reason"),
        rs.getObject("approver_id", UUID.class),
        rs.getObject("created_by", UUID.class),
        rs.getTimestamp("created_at").toInstant(),
        decided == null ? null : decided.toInstant(),
        rs.getObject("decided_by", UUID.class),
        rs.getString("cause_decided"),
        rs.getString("decision_note"),
        rs.getBoolean("escalated"),
        rs.getLong("row_version"));
  }
}
