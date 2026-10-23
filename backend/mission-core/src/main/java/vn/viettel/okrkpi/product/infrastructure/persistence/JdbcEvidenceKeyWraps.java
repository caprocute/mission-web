package vn.viettel.okrkpi.product.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.product.application.e2ee.port.EvidenceKeyWraps;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@link EvidenceKeyWraps} trên {@code product.e2ee_key_wrap}. */
public class JdbcEvidenceKeyWraps implements EvidenceKeyWraps {

  static final String OBJECT_TYPE = "EVIDENCE_FILE";
  private static final String COLUMNS =
      "recipient_person_id, recipient_key_fingerprint, wrap_alg, wrapped_cek, granted_by,"
          + " created_at, revoked_at";

  private final JdbcClient jdbc;

  public JdbcEvidenceKeyWraps(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<StoredWrap> of(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM product.e2ee_key_wrap WHERE tenant_id = ? AND object_type = ?"
                + " AND object_id = ? ORDER BY created_at, id")
        .params(tenant.value(), OBJECT_TYPE, versionId)
        .query((rs, i) -> wrap(rs))
        .list();
  }

  @Override
  public Optional<StoredWrap> activeFor(TenantId tenant, UUID versionId, UUID personId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM product.e2ee_key_wrap WHERE tenant_id = ? AND object_type = ?"
                + " AND object_id = ? AND recipient_person_id = ? AND revoked_at IS NULL"
                + " ORDER BY created_at DESC, id DESC LIMIT 1")
        .params(tenant.value(), OBJECT_TYPE, versionId, personId)
        .query((rs, i) -> wrap(rs))
        .optional();
  }

  @Override
  public void upsert(TenantId tenant, UUID versionId, List<NewWrap> wraps, UUID actor, Instant at) {
    for (NewWrap w : wraps) {
      jdbc.sql(
              "INSERT INTO product.e2ee_key_wrap (id, created_at, created_by, updated_at,"
                  + " updated_by, object_type, object_id, recipient_person_id,"
                  + " recipient_key_fingerprint, wrap_alg, wrapped_cek, granted_by) VALUES (?, ?,"
                  + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, object_id,"
                  + " recipient_key_fingerprint) DO UPDATE SET recipient_person_id ="
                  + " EXCLUDED.recipient_person_id, wrap_alg = EXCLUDED.wrap_alg, wrapped_cek ="
                  + " EXCLUDED.wrapped_cek, granted_by = EXCLUDED.granted_by, revoked_at = NULL,"
                  + " created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at,"
                  + " updated_by = EXCLUDED.updated_by,"
                  + " row_version = product.e2ee_key_wrap.row_version + 1")
          .params(
              Ids.newUuidV7(),
              Timestamp.from(at),
              actor,
              Timestamp.from(at),
              actor,
              OBJECT_TYPE,
              versionId,
              w.recipientId(),
              w.fingerprint(),
              w.algorithm(),
              w.wrappedKey(),
              actor)
          .update();
    }
  }

  private static StoredWrap wrap(ResultSet rs) throws SQLException {
    Timestamp revoked = rs.getTimestamp("revoked_at");
    return new StoredWrap(
        rs.getObject("recipient_person_id", UUID.class),
        rs.getBytes("recipient_key_fingerprint"),
        rs.getString("wrap_alg"),
        rs.getBytes("wrapped_cek"),
        rs.getObject("granted_by", UUID.class),
        rs.getTimestamp("created_at").toInstant(),
        revoked == null ? null : revoked.toInstant());
  }
}
