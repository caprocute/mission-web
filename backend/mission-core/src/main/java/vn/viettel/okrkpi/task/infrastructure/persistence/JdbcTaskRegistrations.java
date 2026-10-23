package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.assign.port.TaskRegistrations;

/** {@link TaskRegistrations} trên {@code task.plan}, {@code plan_version}, {@code plan_item}. */
public class JdbcTaskRegistrations implements TaskRegistrations {

  static final String CHANGE_REASON = "Đăng ký nhiệm vụ";

  private final JdbcClient jdbc;

  public JdbcTaskRegistrations(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<OpenAdjustment> openAdjustment(
      TenantId tenant, int periodBucket, UUID periodId, UUID unitId) {
    return jdbc.sql(
            "SELECT p.id, v.id, v.created_by FROM task.plan p JOIN task.plan_version v"
                + " ON v.period_bucket = p.period_bucket AND v.tenant_id = p.tenant_id"
                + " AND v.plan_id = p.id WHERE p.period_bucket = ? AND p.tenant_id = ?"
                + " AND p.period_id = ? AND p.owner_unit_id = ? AND p.plan_type = 'TAP_THE'"
                + " AND v.status = 'NHAP' AND v.version_no > 1"
                + " ORDER BY v.version_no DESC LIMIT 1")
        .params(periodBucket, tenant.value(), periodId, unitId)
        .query(
            (rs, i) ->
                new OpenAdjustment(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class)))
        .optional();
  }

  @Override
  public Optional<UUID> planner(TenantId tenant, int periodBucket, UUID periodId, UUID unitId) {
    return jdbc.sql(
            "SELECT v.created_by FROM task.plan p JOIN task.plan_version v"
                + " ON v.period_bucket = p.period_bucket AND v.tenant_id = p.tenant_id"
                + " AND v.plan_id = p.id WHERE p.period_bucket = ? AND p.tenant_id = ?"
                + " AND p.period_id = ? AND p.owner_unit_id = ? AND p.plan_type = 'TAP_THE'"
                + " ORDER BY v.version_no DESC LIMIT 1")
        .params(periodBucket, tenant.value(), periodId, unitId)
        .query(UUID.class)
        .optional();
  }

  @Override
  public List<Pending> pending(TenantId tenant, int periodBucket, UUID periodId, UUID unitId) {
    return jdbc.sql(
            "SELECT t.id, t.catalog_item_id, t.title, t.task_group, a.person_id,"
                + " t.planned_quantity, t.unit, t.difficulty, t.standard_score, t.due_date"
                + " FROM task.task t LEFT JOIN task.task_assignee a"
                + " ON a.period_bucket = t.period_bucket AND a.tenant_id = t.tenant_id"
                + " AND a.task_id = t.id AND a.role = 'CHU_TRI' AND a.valid_to = 'infinity'"
                + " WHERE t.period_bucket = ? AND t.tenant_id = ? AND t.period_id = ?"
                + " AND t.org_unit_id = ? AND t.source = 'DANG_KY' AND t.plan_item_id IS NULL"
                + " AND t.status NOT IN ('NHAP', 'HUY') ORDER BY t.created_at, t.id")
        .params(periodBucket, tenant.value(), periodId, unitId)
        .query(
            (rs, i) ->
                new Pending(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getObject(5, UUID.class),
                    rs.getBigDecimal(6),
                    rs.getString(7),
                    (Integer) rs.getObject(8, Integer.class),
                    rs.getBigDecimal(9),
                    rs.getObject(10, LocalDate.class)))
        .list();
  }

  @Override
  public UUID addItem(
      TenantId tenant,
      int periodBucket,
      UUID periodId,
      OpenAdjustment target,
      Pending task,
      UUID actor,
      Instant at) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO task.plan_item (id, created_by, updated_by, period_id, period_bucket,"
                + " plan_version_id, item_no, catalog_item_id, title, task_group,"
                + " lead_person_id, planned_quantity, unit, difficulty, standard_score, due_date,"
                + " change_reason, coefficient_levels, axis_code, created_at, updated_at)"
                + " SELECT ?, ?, ?, ?, ?, ?, coalesce((SELECT max(item_no) FROM task.plan_item"
                + " WHERE period_bucket = ? AND tenant_id = ? AND plan_version_id = ?), 0) + 1,"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, coalesce((SELECT c.coefficient_levels FROM"
                + " task.catalog_item c WHERE c.tenant_id = ? AND c.id = ?), '{}'),"
                + " (SELECT c.axis_code FROM task.catalog_item c WHERE c.tenant_id = ?"
                + " AND c.id = ?), ?, ?")
        .params(
            id,
            actor,
            actor,
            periodId,
            periodBucket,
            target.versionId(),
            periodBucket,
            tenant.value(),
            target.versionId(),
            task.catalogItemId(),
            task.title(),
            task.taskGroup(),
            task.leadPersonId(),
            task.quantity(),
            task.unit() == null ? "" : task.unit(),
            task.difficulty(),
            task.standardScore(),
            task.dueDate(),
            CHANGE_REASON,
            tenant.value(),
            task.catalogItemId(),
            tenant.value(),
            task.catalogItemId(),
            Timestamp.from(at),
            Timestamp.from(at))
        .update();
    jdbc.sql(
            "UPDATE task.plan_version SET row_version = row_version + 1, updated_by = ?,"
                + " updated_at = ? WHERE period_bucket = ? AND tenant_id = ? AND id = ?")
        .params(actor, Timestamp.from(at), periodBucket, tenant.value(), target.versionId())
        .update();
    jdbc.sql(
            "UPDATE task.task SET plan_item_id = ?, updated_at = ?, row_version = row_version + 1"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ?")
        .params(id, Timestamp.from(at), periodBucket, tenant.value(), task.taskId())
        .update();
    return id;
  }
}
