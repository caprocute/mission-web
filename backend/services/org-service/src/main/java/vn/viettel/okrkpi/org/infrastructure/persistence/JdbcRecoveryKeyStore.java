package vn.viettel.okrkpi.org.infrastructure.persistence;

import static vn.viettel.okrkpi.org.infrastructure.persistence.JdbcPublicKeyStore.utc;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.port.RecoveryKeyStore;

/** Adapter {@code org.recovery_key} và {@code org.e2ee_recovery_log} (nhật ký chỉ thêm). */
@Repository
public class JdbcRecoveryKeyStore implements RecoveryKeyStore {

  private static final String KEY_COLUMNS = "id, key_alg, public_key_spki, fingerprint, threshold, custodian_ids, "
      + "ceremony_ref, status, valid_from, valid_to, sig_key_version, signature, signed_at, row_version";
  private static final String LOG_COLUMNS = "id, recovery_key_id, object_type, object_id, new_recipient_id, "
      + "new_key_fingerprint, custodian_ids, reason, ceremony_ref, performed_at, sig_key_version, signature, "
      + "signed_at, created_by, created_at";

  private final JdbcClient jdbc;

  public JdbcRecoveryKeyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<StoredRecoveryKey> active(boolean lock) {
    return jdbc.sql("SELECT " + KEY_COLUMNS + " FROM org.recovery_key WHERE tenant_id = :tenant "
            + "AND status = 'HIEU_LUC'" + (lock ? " FOR UPDATE" : ""))
        .param("tenant", Sql.tenant()).query((rs, n) -> key(rs)).optional();
  }

  @Override
  public Optional<StoredRecoveryKey> find(UUID id) {
    return jdbc.sql("SELECT " + KEY_COLUMNS + " FROM org.recovery_key WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", Sql.tenant()).param("id", id).query((rs, n) -> key(rs)).optional();
  }

  @Override
  public boolean fingerprintExists(byte[] fingerprint) {
    return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM org.recovery_key "
            + "WHERE tenant_id = :tenant AND fingerprint = :fp)")
        .param("tenant", Sql.tenant()).param("fp", fingerprint).query(Boolean.class).single());
  }

  @Override
  public void insert(StoredRecoveryKey k, UUID actor) {
    jdbc.sql("INSERT INTO org.recovery_key (id, tenant_id, key_alg, public_key_spki, fingerprint, threshold, "
            + "custodian_ids, ceremony_ref, status, valid_from, valid_to, sig_key_version, signature, signed_at, "
            + "created_by, updated_by) VALUES (:id, :tenant, :alg, :spki, :fp, :threshold, CAST(:custodians AS uuid[]), "
            + ":ceremony, :status, :from, :to, :kv, :sig, :signed, :actor, :actor)")
        .param("id", k.id()).param("tenant", Sql.tenant()).param("alg", k.keyAlg()).param("spki", k.spki())
        .param("fp", k.fingerprint()).param("threshold", k.threshold())
        .param("custodians", Sql.uuidArray(k.custodianIds())).param("ceremony", k.ceremonyRef())
        .param("status", k.status()).param("from", utc(k.validFrom())).param("to", utc(k.validTo()))
        .param("kv", k.sigKeyVersion()).param("sig", k.signature()).param("signed", utc(k.signedAt()))
        .param("actor", actor)
        .update();
  }

  @Override
  public void retire(UUID id, UUID actor, Instant at) {
    jdbc.sql("UPDATE org.recovery_key SET status = 'THU_HOI', valid_to = :at, updated_at = :at, updated_by = :actor, "
            + "row_version = row_version + 1 WHERE tenant_id = :tenant AND id = :id AND status = 'HIEU_LUC'")
        .param("at", utc(at)).param("actor", actor).param("tenant", Sql.tenant()).param("id", id)
        .update();
  }

  @Override
  public void insertLog(StoredRecovery r, UUID actor) {
    jdbc.sql("INSERT INTO org.e2ee_recovery_log (id, tenant_id, recovery_key_id, object_type, object_id, "
            + "new_recipient_id, new_key_fingerprint, custodian_ids, reason, ceremony_ref, performed_at, "
            + "sig_key_version, signature, signed_at, created_at, created_by) VALUES (:id, :tenant, :key, :type, "
            + ":object, :recipient, :fp, CAST(:custodians AS uuid[]), :reason, :ceremony, :performed, :kv, :sig, "
            + ":signed, :created, :actor)")
        .param("id", r.id()).param("tenant", Sql.tenant()).param("key", r.recoveryKeyId())
        .param("type", r.objectType()).param("object", r.objectId()).param("recipient", r.newRecipientId())
        .param("fp", r.newKeyFingerprint()).param("custodians", Sql.uuidArray(r.custodianIds()))
        .param("reason", r.reason()).param("ceremony", r.ceremonyRef()).param("performed", utc(r.performedAt()))
        .param("kv", r.sigKeyVersion()).param("sig", r.signature()).param("signed", utc(r.signedAt()))
        .param("created", utc(r.recordedAt())).param("actor", actor)
        .update();
  }

  @Override
  public List<StoredRecovery> logs(UUID objectId, Instant afterAt, UUID afterId, int limit) {
    return jdbc.sql("SELECT " + LOG_COLUMNS + " FROM org.e2ee_recovery_log WHERE tenant_id = :tenant "
            + "AND (CAST(:object AS uuid) IS NULL OR object_id = :object) "
            + "AND (CAST(:afterAt AS timestamptz) IS NULL OR (created_at, id) < (:afterAt, :afterId)) "
            + "ORDER BY created_at DESC, id DESC LIMIT :limit")
        .param("tenant", Sql.tenant()).param("object", objectId).param("afterAt", utc(afterAt))
        .param("afterId", afterId).param("limit", limit)
        .query((rs, n) -> recovery(rs))
        .list();
  }

  @Override
  public Optional<StoredRecovery> log(UUID id) {
    return jdbc.sql("SELECT " + LOG_COLUMNS + " FROM org.e2ee_recovery_log WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", Sql.tenant()).param("id", id)
        .query((rs, n) -> recovery(rs))
        .optional();
  }

  private static StoredRecovery recovery(ResultSet rs) throws SQLException {
    return new StoredRecovery(Sql.uuid(rs, "id"), Sql.uuid(rs, "recovery_key_id"),
        rs.getString("object_type"), Sql.uuid(rs, "object_id"), Sql.uuid(rs, "new_recipient_id"),
        rs.getBytes("new_key_fingerprint"), uuids(rs, "custodian_ids"), rs.getString("reason"),
        rs.getString("ceremony_ref"), instant(rs, "performed_at"), rs.getInt("sig_key_version"),
        rs.getBytes("signature"), instant(rs, "signed_at"), Sql.uuid(rs, "created_by"),
        instant(rs, "created_at"));
  }

  private static StoredRecoveryKey key(ResultSet rs) throws SQLException {
    return new StoredRecoveryKey(Sql.uuid(rs, "id"), rs.getString("key_alg"), rs.getBytes("public_key_spki"),
        rs.getBytes("fingerprint"), rs.getInt("threshold"), uuids(rs, "custodian_ids"), rs.getString("ceremony_ref"),
        rs.getString("status"), instant(rs, "valid_from"), instant(rs, "valid_to"), rs.getInt("sig_key_version"),
        rs.getBytes("signature"), instant(rs, "signed_at"), rs.getInt("row_version"));
  }

  private static Instant instant(ResultSet rs, String col) throws SQLException {
    OffsetDateTime t = Sql.ts(rs, col);
    return t == null ? null : t.toInstant();
  }

  private static List<UUID> uuids(ResultSet rs, String col) throws SQLException {
    Array array = rs.getArray(col);
    return array == null ? List.of() : List.of((UUID[]) array.getArray());
  }
}
