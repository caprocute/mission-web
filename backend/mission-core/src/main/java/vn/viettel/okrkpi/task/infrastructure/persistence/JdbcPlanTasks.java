package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.fromplan.port.PlanTasks;

/** {@link PlanTasks} trên {@code task.task} (CORE-02-11). */
public class JdbcPlanTasks implements PlanTasks {

  private final JdbcClient jdbc;

  public JdbcPlanTasks(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<LinkedTask> linked(TenantId tenant, int periodBucket, Collection<UUID> planItemIds) {
    if (planItemIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT id, code, plan_item_id, status, row_version FROM task.task"
                + " WHERE period_bucket = ? AND tenant_id = ? AND plan_item_id = ANY(?)"
                + " ORDER BY CASE status WHEN 'HUY' THEN 1 ELSE 0 END, created_at DESC, id")
        .params(periodBucket, tenant.value(), planItemIds.toArray(UUID[]::new))
        .query(
            (rs, i) ->
                new LinkedTask(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getObject(3, UUID.class),
                    rs.getString(4),
                    rs.getLong(5)))
        .list();
  }

  @Override
  public void relink(
      TenantId tenant, int periodBucket, UUID taskId, UUID planItemId, UUID actor, Instant at) {
    jdbc.sql(
            "UPDATE task.task SET plan_item_id = ?, updated_by = ?, updated_at = ?,"
                + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ?"
                + " AND id = ?")
        .params(planItemId, actor, Timestamp.from(at), periodBucket, tenant.value(), taskId)
        .update();
  }

  @Override
  public boolean refreshAssigned(
      TenantId tenant, int periodBucket, UUID taskId, ItemContent c, UUID actor, Instant at) {
    return jdbc.sql(
                "UPDATE task.task SET plan_item_id = ?, title = ?, product_name = ?,"
                    + " planned_quantity = ?, unit = ?, due_date = ?, difficulty = ?,"
                    + " standard_score = ?, updated_by = ?, updated_at = ?,"
                    + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ?"
                    + " AND id = ? AND status = 'DA_GIAO'")
            .params(
                c.planItemId(),
                c.title(),
                c.productName(),
                c.quantity(),
                c.unit() == null ? "" : c.unit(),
                c.dueDate(),
                c.difficulty(),
                c.standardScore(),
                actor,
                Timestamp.from(at),
                periodBucket,
                tenant.value(),
                taskId)
            .update()
        == 1;
  }
}
