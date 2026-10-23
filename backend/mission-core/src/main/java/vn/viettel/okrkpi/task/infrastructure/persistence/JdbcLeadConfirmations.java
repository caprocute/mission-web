package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.fromplan.port.LeadConfirmations;

/** {@link LeadConfirmations} trên {@code task.plan_item_confirmation} (bổ sung, CORE-02-11). */
public class JdbcLeadConfirmations implements LeadConfirmations {

  /**
   * Dòng kèm đơn vị lập, trạng thái phiên bản và xác nhận của chính dòng hoặc của dòng gốc cùng đơn
   * vị chủ trì.
   */
  private static final String LINES =
      "SELECT i.id, i.period_bucket, i.item_no, p.owner_unit_id, i.lead_unit_id, v.status,"
          + " COALESCE((SELECT c.status FROM task.plan_item_confirmation c"
          + " WHERE c.tenant_id = i.tenant_id AND c.plan_item_id = i.id"
          + " AND c.lead_unit_id = i.lead_unit_id),"
          + " (SELECT c.status FROM task.plan_item_confirmation c"
          + " WHERE c.tenant_id = i.tenant_id AND c.plan_item_id = i.origin_item_id"
          + " AND c.lead_unit_id = i.lead_unit_id)) AS confirmation"
          + " FROM task.plan_item i JOIN task.plan_version v ON v.period_bucket = i.period_bucket"
          + " AND v.tenant_id = i.tenant_id AND v.id = i.plan_version_id"
          + " JOIN task.plan p ON p.period_bucket = v.period_bucket AND p.tenant_id = v.tenant_id"
          + " AND p.id = v.plan_id WHERE i.tenant_id = ? AND i.id = ANY(?)";

  private final JdbcClient jdbc;

  public JdbcLeadConfirmations(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<LineRow> lines(TenantId tenant, Collection<UUID> itemIds) {
    return jdbc.sql(LINES + " ORDER BY i.item_no")
        .params(tenant.value(), itemIds.toArray(UUID[]::new))
        .query(JdbcLeadConfirmations::row)
        .list();
  }

  @Override
  public Optional<LineRow> line(TenantId tenant, UUID itemId) {
    return lines(tenant, List.of(itemId)).stream().findFirst();
  }

  @Override
  public void record(
      TenantId tenant, LineRow line, String status, String reason, UUID actor, Instant at) {
    jdbc.sql(
            "INSERT INTO task.plan_item_confirmation (id, tenant_id, created_by, updated_by,"
                + " period_bucket, plan_item_id, lead_unit_id, status, reason, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (tenant_id, plan_item_id, lead_unit_id) DO UPDATE SET"
                + " status = EXCLUDED.status, reason = EXCLUDED.reason,"
                + " updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at,"
                + " row_version = task.plan_item_confirmation.row_version + 1")
        .params(
            UUID.randomUUID(),
            tenant.value(),
            actor,
            actor,
            line.periodBucket(),
            line.itemId(),
            line.leadUnitId(),
            status,
            reason,
            Timestamp.from(at),
            Timestamp.from(at))
        .update();
  }

  private static LineRow row(ResultSet rs, int i) throws SQLException {
    return new LineRow(
        rs.getObject(1, UUID.class),
        rs.getInt(2),
        rs.getInt(3),
        rs.getObject(4, UUID.class),
        rs.getObject(5, UUID.class),
        rs.getString(6),
        rs.getString(7));
  }
}
