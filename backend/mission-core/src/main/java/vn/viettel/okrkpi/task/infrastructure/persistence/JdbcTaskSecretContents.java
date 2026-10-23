package vn.viettel.okrkpi.task.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.secret.port.TaskSecretContents;
import vn.viettel.okrkpi.task.domain.secret.KeyWrap;
import vn.viettel.okrkpi.task.domain.secret.SecretContentRules.Sealed;

/** {@link TaskSecretContents} trên {@code task.task_secret_content}, {@code task.e2ee_key_wrap}. */
public class JdbcTaskSecretContents implements TaskSecretContents {

  static final String OBJECT_TYPE = "TASK_CONTENT";

  private final JdbcClient jdbc;

  public JdbcTaskSecretContents(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<TaskFacts> task(TenantId tenant, UUID taskId) {
    return jdbc.sql(
            "SELECT id, period_id, period_bucket, is_restricted, assigner_id, acceptor_id"
                + " FROM task.task WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), taskId)
        .query(
            (rs, i) -> {
              int bucket = rs.getInt("period_bucket");
              return new TaskFacts(
                  rs.getObject("id", UUID.class),
                  rs.getObject("period_id", UUID.class),
                  bucket,
                  rs.getBoolean("is_restricted"),
                  rs.getObject("assigner_id", UUID.class),
                  rs.getObject("acceptor_id", UUID.class),
                  assignees(tenant, bucket, taskId));
            })
        .optional();
  }

  private Set<UUID> assignees(TenantId tenant, int bucket, UUID taskId) {
    return new LinkedHashSet<>(
        jdbc.sql(
                "SELECT person_id FROM task.task_assignee WHERE period_bucket = ? AND tenant_id = ?"
                    + " AND task_id = ? AND person_id IS NOT NULL AND removed_at IS NULL"
                    + " AND recorded_to = 'infinity'")
            .params(bucket, tenant.value(), taskId)
            .query(UUID.class)
            .list());
  }

  @Override
  public Optional<Content> content(TenantId tenant, int periodBucket, UUID taskId, boolean lock) {
    return jdbc.sql(
            "SELECT id, task_id, ciphertext, nonce, aad_hash, content_sha256,"
                + " sender_key_fingerprint, updated_at, row_version FROM task.task_secret_content"
                + " WHERE period_bucket = ? AND tenant_id = ? AND task_id = ?"
                + (lock ? " FOR UPDATE" : ""))
        .params(periodBucket, tenant.value(), taskId)
        .query(
            (rs, i) ->
                new Content(
                    rs.getObject("id", UUID.class),
                    rs.getObject("task_id", UUID.class),
                    new Sealed(
                        rs.getBytes("ciphertext"),
                        rs.getBytes("nonce"),
                        rs.getBytes("aad_hash"),
                        rs.getBytes("content_sha256")),
                    rs.getBytes("sender_key_fingerprint"),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getLong("row_version")))
        .optional();
  }

  @Override
  public boolean insert(
      TenantId tenant,
      TaskFacts task,
      UUID id,
      Sealed sealed,
      byte[] sender,
      UUID actor,
      Instant at) {
    try {
      jdbc.sql(
              "INSERT INTO task.task_secret_content (id, created_at, created_by, updated_at,"
                  + " updated_by, period_id, period_bucket, task_id, ciphertext, nonce, aad_hash,"
                  + " content_sha256, sender_key_fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                  + " ?, ?, ?, ?)")
          .params(
              id,
              Timestamp.from(at),
              actor,
              Timestamp.from(at),
              actor,
              task.periodId(),
              task.periodBucket(),
              task.id(),
              sealed.ciphertext(),
              sealed.nonce(),
              sealed.aadHash(),
              sealed.sha256(),
              sender)
          .update();
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  @Override
  public long update(
      TenantId tenant,
      int periodBucket,
      UUID taskId,
      long expected,
      Sealed sealed,
      byte[] sender,
      UUID actor,
      Instant at) {
    return jdbc.sql(
            "UPDATE task.task_secret_content SET ciphertext = ?, nonce = ?, aad_hash = ?,"
                + " content_sha256 = ?, sender_key_fingerprint = ?, updated_at = ?, updated_by = ?,"
                + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ?"
                + " AND task_id = ? AND row_version = ? RETURNING row_version")
        .params(
            sealed.ciphertext(),
            sealed.nonce(),
            sealed.aadHash(),
            sealed.sha256(),
            sender,
            Timestamp.from(at),
            actor,
            periodBucket,
            tenant.value(),
            taskId,
            expected)
        .query(Long.class)
        .optional()
        .orElse(-1L);
  }

  @Override
  public long touch(TenantId tenant, int periodBucket, UUID taskId, UUID actor, Instant at) {
    return jdbc.sql(
            "UPDATE task.task_secret_content SET updated_at = ?, updated_by = ?,"
                + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ?"
                + " AND task_id = ? RETURNING row_version")
        .params(Timestamp.from(at), actor, periodBucket, tenant.value(), taskId)
        .query(Long.class)
        .single();
  }

  @Override
  public List<KeyWrap> wraps(TenantId tenant, UUID taskId) {
    return jdbc.sql(
            "SELECT recipient_person_id, recipient_key_fingerprint, wrap_alg, ephemeral_pub,"
                + " wrapped_cek, granted_by, created_at, revoked_at FROM task.e2ee_key_wrap"
                + " WHERE tenant_id = ? AND object_type = ? AND object_id = ?"
                + " ORDER BY created_at, id")
        .params(tenant.value(), OBJECT_TYPE, taskId)
        .query((rs, i) -> wrap(rs))
        .list();
  }

  @Override
  public void upsert(TenantId tenant, UUID taskId, List<KeyWrap> wraps, UUID actor, Instant at) {
    for (KeyWrap w : wraps) {
      jdbc.sql(
              "INSERT INTO task.e2ee_key_wrap (id, created_at, created_by, updated_at, updated_by,"
                  + " object_type, object_id, recipient_person_id, recipient_key_fingerprint,"
                  + " wrap_alg, wrapped_cek, ephemeral_pub, granted_by) VALUES (?, ?, ?, ?, ?, ?,"
                  + " ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, object_id,"
                  + " recipient_key_fingerprint) DO UPDATE SET recipient_person_id ="
                  + " EXCLUDED.recipient_person_id, wrap_alg = EXCLUDED.wrap_alg, wrapped_cek ="
                  + " EXCLUDED.wrapped_cek, ephemeral_pub = EXCLUDED.ephemeral_pub, granted_by ="
                  + " EXCLUDED.granted_by, revoked_at = NULL, created_at = EXCLUDED.created_at,"
                  + " updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by,"
                  + " row_version = task.e2ee_key_wrap.row_version + 1")
          .params(
              Ids.newUuidV7(),
              Timestamp.from(at),
              actor,
              Timestamp.from(at),
              actor,
              OBJECT_TYPE,
              taskId,
              w.recipientId(),
              w.fingerprint(),
              w.algorithm(),
              w.wrappedKey(),
              w.ephemeralPublicKey(),
              actor)
          .update();
    }
  }

  @Override
  public int revokeExcept(TenantId tenant, UUID taskId, Set<String> keep, UUID actor, Instant at) {
    String[] fingerprints = keep.toArray(String[]::new);
    return jdbc.sql(
            "UPDATE task.e2ee_key_wrap SET revoked_at = ?, updated_at = ?, updated_by = ?,"
                + " row_version = row_version + 1 WHERE tenant_id = ? AND object_type = ?"
                + " AND object_id = ? AND revoked_at IS NULL"
                + " AND NOT (encode(recipient_key_fingerprint, 'hex') = ANY (?::text[]))")
        .params(
            Timestamp.from(at),
            Timestamp.from(at),
            actor,
            tenant.value(),
            OBJECT_TYPE,
            taskId,
            fingerprints)
        .update();
  }

  @Override
  public int revokePerson(TenantId tenant, UUID taskId, UUID personId, UUID actor, Instant at) {
    return jdbc.sql(
            "UPDATE task.e2ee_key_wrap SET revoked_at = ?, updated_at = ?, updated_by = ?,"
                + " row_version = row_version + 1 WHERE tenant_id = ? AND object_type = ?"
                + " AND object_id = ? AND recipient_person_id = ? AND revoked_at IS NULL")
        .params(
            Timestamp.from(at),
            Timestamp.from(at),
            actor,
            tenant.value(),
            OBJECT_TYPE,
            taskId,
            personId)
        .update();
  }

  private static KeyWrap wrap(ResultSet rs) throws SQLException {
    Timestamp revoked = rs.getTimestamp("revoked_at");
    return new KeyWrap(
        rs.getObject("recipient_person_id", UUID.class),
        rs.getBytes("recipient_key_fingerprint"),
        rs.getString("wrap_alg"),
        rs.getBytes("ephemeral_pub"),
        rs.getBytes("wrapped_cek"),
        rs.getObject("granted_by", UUID.class),
        rs.getTimestamp("created_at").toInstant(),
        revoked == null ? null : revoked.toInstant());
  }
}
