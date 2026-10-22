package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.overdue.port.OverdueTasks;

/**
 * {@link OverdueTasks} trên {@code taskDataSource}: lô nhiệm vụ đang mở theo {@code id}, hạn tính
 * điểm là {@code score_due_date}, nếu trống thì hạn gia hạn rồi hạn gốc.
 */
public class JdbcOverdueTasks implements OverdueTasks {

  private final JdbcClient jdbc;

  public JdbcOverdueTasks(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<OpenTask> page(TenantId tenant, UUID afterId, int limit) {
    return jdbc.sql(
            "SELECT t.id, t.period_bucket, t.code, t.title, t.assigner_id,"
                + " (SELECT a.person_id FROM task.task_assignee a WHERE a.period_bucket ="
                + " t.period_bucket AND a.tenant_id = t.tenant_id AND a.task_id = t.id"
                + " AND a.role = 'CHU_TRI' AND a.removed_at IS NULL AND a.recorded_to = 'infinity'"
                + " AND a.valid_to = 'infinity' AND a.person_id IS NOT NULL"
                + " ORDER BY a.valid_from DESC LIMIT 1) AS lead_id,"
                + " coalesce(t.score_due_date, t.extended_due_date, t.due_date) AS score_due,"
                + " t.status, t.is_overdue, t.row_version FROM task.task t WHERE t.tenant_id = ?"
                + " AND t.status IN ('DA_GIAO', 'DANG_THUC_HIEN', 'TAM_DUNG')"
                + " AND (CAST(? AS uuid) IS NULL OR t.id > ?) ORDER BY t.id LIMIT ?")
        .params(tenant.value(), afterId, afterId, limit)
        .query(
            (rs, i) ->
                new OpenTask(
                    rs.getObject("id", UUID.class),
                    rs.getInt("period_bucket"),
                    rs.getString("code"),
                    rs.getString("title"),
                    rs.getObject("assigner_id", UUID.class),
                    rs.getObject("lead_id", UUID.class),
                    rs.getDate("score_due").toLocalDate(),
                    rs.getString("status"),
                    rs.getBoolean("is_overdue"),
                    rs.getLong("row_version")))
        .list();
  }

  @Override
  public boolean flag(TenantId tenant, int periodBucket, UUID taskId, boolean overdue) {
    return jdbc.sql(
                "UPDATE task.task SET is_overdue = ? WHERE period_bucket = ? AND tenant_id = ?"
                    + " AND id = ? AND is_overdue <> ?")
            .params(overdue, periodBucket, tenant.value(), taskId, overdue)
            .update()
        == 1;
  }
}
