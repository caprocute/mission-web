package vn.viettel.okrkpi.integ.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.integ.application.port.TransferPackages;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@code integ.transfer_package} (DV-13-10); việc đến hạn của mọi tenant đọc qua hàm quét {@code
 * fn_goi_chuyen_can_xu_ly}.
 */
public class JdbcTransferPackages implements TransferPackages {

  private static final String COLUMNS =
      "id, person_id, direction, peer_cell_code, peer_tenant_code, peer_tenant_id, manifest::text"
          + " AS manifest, object_key, package_sha256, wrapped_key, signature, signed_header,"
          + " status, reason, attempts, next_attempt_at, peer_notified_at, row_version, created_at,"
          + " updated_at";

  private final JdbcClient jdbc;

  public JdbcTransferPackages(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean insert(TenantId tenant, Row r, UUID actor) {
    return jdbc.sql(
                "INSERT INTO integ.transfer_package (id, tenant_id, created_at, created_by,"
                    + " updated_at, updated_by, person_id, direction, peer_cell_code,"
                    + " peer_tenant_code, peer_tenant_id, manifest, object_key, package_sha256,"
                    + " wrapped_key, signature, signed_header, status, reason, attempts,"
                    + " next_attempt_at, peer_notified_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
            .params(
                r.id(),
                tenant.value(),
                ts(r.createdAt()),
                actor,
                ts(r.updatedAt()),
                actor,
                r.personId(),
                r.direction().name(),
                r.peerCell(),
                r.peerTenantCode(),
                r.peerTenantId(),
                r.manifest(),
                r.objectKey(),
                r.packageSha256(),
                r.wrappedKey(),
                r.signature(),
                r.signedHeader(),
                r.status().name(),
                r.reason(),
                r.attempts(),
                ts(r.nextAttemptAt()),
                ts(r.peerNotifiedAt()))
            .update()
        == 1;
  }

  @Override
  public Optional<Row> find(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM integ.transfer_package WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query((rs, i) -> row(rs))
        .optional();
  }

  @Override
  public Optional<Row> lock(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM integ.transfer_package WHERE tenant_id = ? AND id = ?"
                + " FOR UPDATE SKIP LOCKED")
        .params(tenant.value(), id)
        .query((rs, i) -> row(rs))
        .optional();
  }

  @Override
  public void change(TenantId tenant, UUID id, Change c, UUID actor, Instant now) {
    jdbc.sql(
            "UPDATE integ.transfer_package SET status = ?, reason = ?, attempts = ?,"
                + " next_attempt_at = ?, peer_notified_at = coalesce(?, peer_notified_at),"
                + " row_version = row_version + 1, updated_at = ?, updated_by = ?"
                + " WHERE tenant_id = ? AND id = ?")
        .params(
            c.status().name(),
            c.reason(),
            c.attempts(),
            ts(c.nextAttemptAt()),
            ts(c.peerNotifiedAt()),
            ts(now),
            actor,
            tenant.value(),
            id)
        .update();
  }

  @Override
  public List<Due> due(int limit) {
    return jdbc.sql("SELECT tenant_id, id FROM integ.fn_goi_chuyen_can_xu_ly(?)")
        .param(limit)
        .query((rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
        .list();
  }

  private static Row row(ResultSet rs) throws SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getObject("person_id", UUID.class),
        Direction.valueOf(rs.getString("direction")),
        rs.getString("peer_cell_code"),
        rs.getString("peer_tenant_code"),
        rs.getObject("peer_tenant_id", UUID.class),
        rs.getString("manifest"),
        rs.getString("object_key"),
        rs.getBytes("package_sha256"),
        rs.getBytes("wrapped_key"),
        rs.getBytes("signature"),
        rs.getBytes("signed_header"),
        Status.valueOf(rs.getString("status")),
        rs.getString("reason"),
        rs.getInt("attempts"),
        instant(rs, "next_attempt_at"),
        instant(rs, "peer_notified_at"),
        rs.getInt("row_version"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
