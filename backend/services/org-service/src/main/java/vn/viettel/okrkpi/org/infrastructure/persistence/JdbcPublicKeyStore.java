package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.port.PublicKeyStore;

/** Adapter {@code org.person_public_key}: chỉ thêm và đổi trạng thái, không xoá dòng. */
@Repository
public class JdbcPublicKeyStore implements PublicKeyStore {

  private static final String COLUMNS = "id, person_id, key_alg, public_key_spki, fingerprint, source, status, "
      + "valid_from, valid_to, sig_key_version, signature, signed_at, row_version";

  private final JdbcClient jdbc;

  public JdbcPublicKeyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean fingerprintExists(byte[] fingerprint) {
    return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM org.person_public_key "
            + "WHERE tenant_id = :tenant AND fingerprint = :fp)")
        .param("tenant", Sql.tenant()).param("fp", fingerprint).query(Boolean.class).single());
  }

  @Override
  public int countActive(UUID personId, Instant now) {
    return jdbc.sql("SELECT count(*) FROM org.person_public_key WHERE tenant_id = :tenant AND person_id = :person "
            + "AND status = 'HIEU_LUC' AND valid_to > :now")
        .param("tenant", Sql.tenant()).param("person", personId).param("now", utc(now))
        .query(Integer.class).single();
  }

  @Override
  public void insert(StoredKey k, UUID actor) {
    jdbc.sql("INSERT INTO org.person_public_key (id, tenant_id, person_id, key_alg, public_key_spki, fingerprint, "
            + "source, valid_from, valid_to, status, sig_key_version, signature, signed_at, created_by, updated_by) "
            + "VALUES (:id, :tenant, :person, :alg, :spki, :fp, :source, :from, :to, :status, :kv, :sig, :signed, "
            + ":actor, :actor)")
        .param("id", k.id()).param("tenant", Sql.tenant()).param("person", k.personId()).param("alg", k.keyAlg())
        .param("spki", k.spki()).param("fp", k.fingerprint()).param("source", k.source())
        .param("from", utc(k.validFrom())).param("to", utc(k.validTo())).param("status", k.status())
        .param("kv", k.sigKeyVersion()).param("sig", k.signature()).param("signed", utc(k.signedAt()))
        .param("actor", actor)
        .update();
  }

  @Override
  public Optional<StoredKey> find(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM org.person_public_key WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", Sql.tenant()).param("id", id).query((rs, n) -> map(rs)).optional();
  }

  @Override
  public Optional<StoredKey> byFingerprint(byte[] fingerprint) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM org.person_public_key WHERE tenant_id = :tenant AND fingerprint = :fp")
        .param("tenant", Sql.tenant()).param("fp", fingerprint).query((rs, n) -> map(rs)).optional();
  }

  @Override
  public List<StoredKey> byPerson(UUID personId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM org.person_public_key WHERE tenant_id = :tenant "
            + "AND person_id = :person ORDER BY valid_from DESC, id DESC LIMIT 200")
        .param("tenant", Sql.tenant()).param("person", personId).query((rs, n) -> map(rs)).list();
  }

  @Override
  public int revoke(UUID id, int expectedVersion, UUID actor, String reason, Instant at) {
    return jdbc.sql("UPDATE org.person_public_key SET status = 'THU_HOI', revoked_at = :at, revoked_by = :actor, "
            + "revoke_reason = :reason, updated_at = :at, updated_by = :actor, row_version = row_version + 1 "
            + "WHERE tenant_id = :tenant AND id = :id AND row_version = :version AND status <> 'THU_HOI'")
        .param("at", utc(at)).param("actor", actor).param("reason", reason).param("tenant", Sql.tenant())
        .param("id", id).param("version", expectedVersion)
        .update();
  }

  private static StoredKey map(ResultSet rs) throws SQLException {
    return new StoredKey(Sql.uuid(rs, "id"), Sql.uuid(rs, "person_id"), rs.getString("key_alg"),
        rs.getBytes("public_key_spki"), rs.getBytes("fingerprint"), rs.getString("source"), rs.getString("status"),
        Sql.ts(rs, "valid_from").toInstant(), Sql.ts(rs, "valid_to").toInstant(), rs.getInt("sig_key_version"),
        rs.getBytes("signature"), Sql.ts(rs, "signed_at").toInstant(), rs.getInt("row_version"));
  }

  static OffsetDateTime utc(Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }
}
