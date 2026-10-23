package vn.viettel.okrkpi.integ.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.integ.application.port.DeadLetters;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@code integ.dead_letter}: trang keyset theo {@code (created_at, id)}. */
public class JdbcDeadLetters implements DeadLetters {

  private static final String COLUMNS =
      "id, connector_id, direction, ref_message_id, error_code, error_detail, created_at,"
          + " resolved_by, resolved_at";

  private final JdbcClient jdbc;

  public JdbcDeadLetters(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(
      TenantId tenant,
      UUID id,
      UUID connectorId,
      String direction,
      UUID refMessageId,
      String errorCode,
      String errorDetail) {
    jdbc.sql(
            "INSERT INTO integ.dead_letter (id, tenant_id, connector_id, direction, ref_message_id,"
                + " error_code, error_detail) VALUES (?, ?, ?, ?, ?, ?, ?)")
        .params(id, tenant.value(), connectorId, direction, refMessageId, errorCode, errorDetail)
        .update();
  }

  @Override
  public Optional<Letter> find(TenantId tenant, UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM integ.dead_letter WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query(JdbcDeadLetters::map)
        .optional();
  }

  @Override
  public List<Letter> page(
      TenantId tenant,
      UUID connectorId,
      boolean openOnly,
      Instant afterCreatedAt,
      UUID afterId,
      int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT " + COLUMNS + " FROM integ.dead_letter WHERE tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant.value());
    if (connectorId != null) {
      sql.append(" AND connector_id = ?");
      params.add(connectorId);
    }
    if (openOnly) {
      sql.append(" AND resolved_at IS NULL");
    }
    if (afterCreatedAt != null) {
      sql.append(" AND (created_at, id) > (?, ?)");
      params.add(Timestamp.from(afterCreatedAt));
      params.add(afterId);
    }
    sql.append(" ORDER BY created_at, id LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query(JdbcDeadLetters::map).list();
  }

  @Override
  public Optional<String> openErrorCode(TenantId tenant, UUID refMessageId) {
    return jdbc.sql(
            "SELECT error_code FROM integ.dead_letter WHERE tenant_id = ? AND ref_message_id = ?"
                + " AND resolved_at IS NULL ORDER BY created_at DESC LIMIT 1")
        .params(tenant.value(), refMessageId)
        .query(String.class)
        .optional();
  }

  @Override
  public boolean hasOpen(TenantId tenant, UUID refMessageId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM integ.dead_letter WHERE tenant_id = ?"
                + " AND ref_message_id = ? AND resolved_at IS NULL)")
        .params(tenant.value(), refMessageId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public boolean resolve(TenantId tenant, UUID id, UUID actor, Instant at) {
    return jdbc.sql(
                "UPDATE integ.dead_letter SET resolved_by = ?, resolved_at = ?"
                    + " WHERE tenant_id = ? AND id = ? AND resolved_at IS NULL")
            .params(actor, Timestamp.from(at), tenant.value(), id)
            .update()
        == 1;
  }

  private static Letter map(ResultSet rs, int row) throws SQLException {
    Timestamp resolvedAt = rs.getTimestamp("resolved_at");
    return new Letter(
        rs.getObject("id", UUID.class),
        rs.getObject("connector_id", UUID.class),
        rs.getString("direction"),
        rs.getObject("ref_message_id", UUID.class),
        rs.getString("error_code"),
        rs.getString("error_detail"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getObject("resolved_by", UUID.class),
        resolvedAt == null ? null : resolvedAt.toInstant());
  }
}
