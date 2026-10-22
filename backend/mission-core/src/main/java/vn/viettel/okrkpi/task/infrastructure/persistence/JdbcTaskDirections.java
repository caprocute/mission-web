package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.direct.port.TaskDirections;

/** {@link TaskDirections} trên {@code task.task}, {@code task_assignee}, {@code task_state_log}. */
public class JdbcTaskDirections implements TaskDirections {

  private final JdbcClient jdbc;

  public JdbcTaskDirections(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Facts> facts(TenantId tenant, int periodBucket, UUID taskId) {
    return jdbc.sql(
            "SELECT org_unit_id, plan_item_id, is_restricted, is_key_task FROM task.task"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ?")
        .params(periodBucket, tenant.value(), taskId)
        .query(
            (rs, i) ->
                new Facts(
                    rs.getObject("org_unit_id", UUID.class),
                    rs.getObject("plan_item_id", UUID.class),
                    rs.getBoolean("is_restricted"),
                    rs.getBoolean("is_key_task")))
        .optional();
  }

  @Override
  public Optional<Lead> lead(TenantId tenant, int periodBucket, UUID taskId) {
    return jdbc.sql(
            "SELECT id, assignee_type, person_id, org_unit_id, valid_from FROM task.task_assignee"
                + " WHERE period_bucket = ? AND tenant_id = ? AND task_id = ? AND role = 'CHU_TRI'"
                + " AND removed_at IS NULL AND recorded_to = 'infinity'"
                + " ORDER BY valid_from DESC LIMIT 1")
        .params(periodBucket, tenant.value(), taskId)
        .query(
            (rs, i) ->
                new Lead(
                    rs.getObject("id", UUID.class),
                    rs.getString("assignee_type"),
                    rs.getObject("person_id", UUID.class),
                    rs.getObject("org_unit_id", UUID.class),
                    rs.getObject("valid_from", LocalDate.class)))
        .optional();
  }

  @Override
  public boolean remindedSince(
      TenantId tenant,
      int periodBucket,
      UUID taskId,
      UUID actor,
      String transition,
      Instant since) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM task.task_state_log WHERE period_bucket = ?"
                + " AND tenant_id = ? AND task_id = ? AND actor_id = ? AND transition = ?"
                + " AND occurred_at >= ?)")
        .params(periodBucket, tenant.value(), taskId, actor, transition, Timestamp.from(since))
        .query(Boolean.class)
        .single();
  }

  @Override
  public long bumpVersion(
      TenantId tenant, int periodBucket, UUID taskId, long expected, UUID actor, Instant at) {
    int n =
        jdbc.sql(
                "UPDATE task.task SET row_version = row_version + 1, updated_by = ?,"
                    + " updated_at = ? WHERE period_bucket = ? AND tenant_id = ? AND id = ?"
                    + " AND row_version = ?")
            .params(actor, Timestamp.from(at), periodBucket, tenant.value(), taskId, expected)
            .update();
    return n == 0 ? -1 : expected + 1;
  }

  @Override
  public void replaceLead(
      TenantId tenant,
      UUID periodId,
      int periodBucket,
      UUID taskId,
      Lead old,
      UUID newPerson,
      LocalDate from,
      UUID actor,
      Instant at) {
    if (old != null) {
      // Dòng cũ giữ lại làm lịch sử của người chủ trì cũ; hiệu lực kết thúc ở ngày giao lại (ít
      // nhất một ngày để thoả ck_task_assignee__valid khi giao rồi giao lại trong ngày).
      LocalDate end = old.validFrom().isBefore(from) ? from : old.validFrom().plusDays(1);
      jdbc.sql(
              "UPDATE task.task_assignee SET removed_at = ?, valid_to = ?, updated_by = ?,"
                  + " updated_at = ?, row_version = row_version + 1 WHERE period_bucket = ?"
                  + " AND tenant_id = ? AND id = ?")
          .params(
              Timestamp.from(at),
              Date.valueOf(end),
              actor,
              Timestamp.from(at),
              periodBucket,
              tenant.value(),
              old.rowId())
          .update();
    }
    jdbc.sql(
            "INSERT INTO task.task_assignee (id, created_by, updated_by, period_id, period_bucket,"
                + " task_id, assignee_type, person_id, role, valid_from) VALUES (?, ?, ?, ?, ?, ?,"
                + " 'CA_NHAN', ?, 'CHU_TRI', ?)")
        .params(
            UUID.randomUUID(),
            actor,
            actor,
            periodId,
            periodBucket,
            taskId,
            newPerson,
            Date.valueOf(from))
        .update();
  }

  @Override
  public List<LedTask> openTasksLedBy(TenantId tenant, UUID personId) {
    return jdbc.sql(
            "SELECT t.id, t.period_id, t.period_bucket, t.code, t.status, t.assigner_id,"
                + " coalesce(t.score_due_date, t.extended_due_date, t.due_date) AS score_due,"
                + " t.state_machine_version, t.row_version FROM task.task t JOIN task.task_assignee a"
                + " ON a.period_bucket = t.period_bucket AND a.tenant_id = t.tenant_id"
                + " AND a.task_id = t.id WHERE t.tenant_id = ? AND a.person_id = ?"
                + " AND a.role = 'CHU_TRI' AND a.removed_at IS NULL"
                + " AND a.recorded_to = 'infinity' AND a.valid_to = 'infinity'"
                + " AND t.status IN ('DA_GIAO', 'DANG_THUC_HIEN', 'TAM_DUNG') ORDER BY t.code")
        .params(tenant.value(), personId)
        .query(
            (rs, i) ->
                new LedTask(
                    rs.getObject("id", UUID.class),
                    rs.getObject("period_id", UUID.class),
                    rs.getInt("period_bucket"),
                    rs.getString("code"),
                    rs.getString("status"),
                    rs.getObject("assigner_id", UUID.class),
                    rs.getObject("score_due", LocalDate.class),
                    rs.getString("state_machine_version"),
                    rs.getLong("row_version")))
        .list();
  }
}
