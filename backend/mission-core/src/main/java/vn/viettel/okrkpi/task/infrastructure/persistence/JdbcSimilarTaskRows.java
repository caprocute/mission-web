package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.similar.port.SimilarTaskRows;

/** {@link SimilarTaskRows} trên {@code task.task}; trùng dấu vân tay dùng {@code ix_task__dup}. */
public class JdbcSimilarTaskRows implements SimilarTaskRows {

  static final String COLUMNS =
      "id, period_id, org_unit_id, code, title, status, is_restricted,"
          + " coalesce(extended_due_date, due_date) AS due, progress_pct, dup_fingerprint";

  private final JdbcClient jdbc;

  public JdbcSimilarTaskRows(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Probe> probe(TenantId tenant, UUID taskId) {
    return jdbc.sql(
            "SELECT id, period_id, org_unit_id, title, product_name, dup_fingerprint FROM task.task"
                + " WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), taskId)
        .query(
            (rs, i) ->
                new Probe(
                    rs.getObject("id", UUID.class),
                    rs.getObject("period_id", UUID.class),
                    rs.getObject("org_unit_id", UUID.class),
                    rs.getString("title"),
                    rs.getString("product_name"),
                    rs.getBytes("dup_fingerprint")))
        .optional();
  }

  @Override
  public List<Row> byIds(TenantId tenant, UUID periodId, Collection<UUID> ids) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM task.task WHERE tenant_id = ? AND period_id = ? AND id = ANY(?)")
        .params(tenant.value(), periodId, ids.toArray(UUID[]::new))
        .query(JdbcSimilarTaskRows::row)
        .list();
  }

  @Override
  public List<Row> byFingerprint(TenantId tenant, UUID periodId, byte[] fingerprint) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM task.task WHERE tenant_id = ? AND period_id = ? AND dup_fingerprint = ?")
        .params(tenant.value(), periodId, fingerprint)
        .query(JdbcSimilarTaskRows::row)
        .list();
  }

  @Override
  public List<Row> inUnit(TenantId tenant, UUID periodId, UUID unitId, int limit) {
    if (unitId == null) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM task.task WHERE tenant_id = ? AND period_id = ? AND org_unit_id = ?"
                + " AND status <> 'HUY' AND NOT is_restricted ORDER BY created_at DESC LIMIT ?")
        .params(tenant.value(), periodId, unitId, limit)
        .query(JdbcSimilarTaskRows::row)
        .list();
  }

  private static Row row(ResultSet rs, int i) throws SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getObject("org_unit_id", UUID.class),
        rs.getString("code"),
        rs.getString("title"),
        rs.getString("status"),
        rs.getBoolean("is_restricted"),
        rs.getObject("due", LocalDate.class),
        rs.getBigDecimal("progress_pct"),
        rs.getBytes("dup_fingerprint"));
  }
}
