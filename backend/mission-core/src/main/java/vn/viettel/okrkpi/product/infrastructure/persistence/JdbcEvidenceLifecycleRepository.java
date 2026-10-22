package vn.viettel.okrkpi.product.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceLifecycleRepository;
import vn.viettel.okrkpi.product.domain.evidence.DeliverableRollup;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceStatus;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceVersion;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link EvidenceLifecycleRepository} trên {@code productDataSource} (vai trò {@code svc_product},
 * RLS).
 */
public class JdbcEvidenceLifecycleRepository implements EvidenceLifecycleRepository {

  private static final String VERSION_COLUMNS =
      "v.id, v.evidence_id, e.deliverable_id, v.task_id, v.version_no, v.status, v.object_id,"
          + " v.content_sha256, v.scan_status, v.ai_prefill, v.user_confirmed_at, v.submitted_by,"
          + " v.submitted_at, v.accepted_at, v.note, v.excluded_period_id, v.row_version,"
          + " v.period_id, v.period_bucket";

  private static final String JOIN_EVIDENCE =
      " JOIN product.evidence e ON e.period_bucket = v.period_bucket AND e.tenant_id = v.tenant_id"
          + " AND e.id = v.evidence_id";

  private final JdbcClient jdbc;

  public JdbcEvidenceLifecycleRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<LockedVersion> lockVersion(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM product.evidence_version v"
                + JOIN_EVIDENCE
                + " WHERE v.tenant_id = ? AND v.id = ? FOR UPDATE OF v")
        .params(tenant.value(), versionId)
        .query(
            (rs, i) ->
                new LockedVersion(
                    version(rs), rs.getObject("period_id", UUID.class), rs.getInt("period_bucket")))
        .optional();
  }

  @Override
  public Optional<Long> updateVersion(
      TenantId tenant,
      int periodBucket,
      EvidenceVersion v,
      long expectedRowVersion,
      UUID actorId,
      Instant at) {
    return jdbc.sql(
            "UPDATE product.evidence_version SET status = ?, object_id = ?, content_sha256 = ?,"
                + " scan_status = ?, ai_prefill = ?, user_confirmed_at = ?, submitted_by = ?,"
                + " submitted_at = ?, accepted_at = ?, note = ?, updated_by = ?, updated_at = ?,"
                + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ? AND id"
                + " = ? AND row_version = ? RETURNING row_version")
        .params(
            v.status().name(),
            v.objectId(),
            v.contentSha256(),
            v.scanStatus(),
            v.aiPrefill(),
            ts(v.userConfirmedAt()),
            v.submittedBy(),
            ts(v.submittedAt()),
            ts(v.acceptedAt()),
            v.note(),
            actorId,
            ts(at),
            periodBucket,
            tenant.value(),
            v.id(),
            expectedRowVersion)
        .query(Long.class)
        .optional();
  }

  @Override
  public void updateEvidenceCurrent(
      TenantId tenant,
      int periodBucket,
      UUID evidenceId,
      UUID versionId,
      String status,
      UUID actorId,
      Instant at) {
    jdbc.sql(
            "UPDATE product.evidence SET current_version_id = ?, current_status = ?, updated_by ="
                + " ?, updated_at = ?, row_version = row_version + 1 WHERE period_bucket = ? AND"
                + " tenant_id = ? AND id = ?")
        .params(versionId, status, actorId, ts(at), periodBucket, tenant.value(), evidenceId)
        .update();
  }

  @Override
  public List<DeliverableRow> lockDeliverablesOfTask(TenantId tenant, UUID taskId) {
    return jdbc.sql(
            "SELECT id, period_bucket, quantity_planned FROM product.deliverable WHERE tenant_id"
                + " = ? AND task_id = ? ORDER BY id FOR UPDATE")
        .params(tenant.value(), taskId)
        .query(
            (rs, i) ->
                new DeliverableRow(
                    rs.getObject("id", UUID.class),
                    rs.getInt("period_bucket"),
                    rs.getBigDecimal("quantity_planned")))
        .list();
  }

  @Override
  public List<EvidenceVersion> currentVersionsOfTask(TenantId tenant, UUID taskId) {
    return jdbc.sql(
            "SELECT DISTINCT ON (v.evidence_id) "
                + VERSION_COLUMNS
                + " FROM product.evidence_version v"
                + JOIN_EVIDENCE
                + " WHERE v.tenant_id = ? AND v.task_id = ? ORDER BY v.evidence_id, v.version_no"
                + " DESC")
        .params(tenant.value(), taskId)
        .query((rs, i) -> version(rs))
        .list();
  }

  @Override
  public void updateDeliverable(
      TenantId tenant,
      int periodBucket,
      UUID deliverableId,
      DeliverableRollup rollup,
      UUID actorId,
      Instant at) {
    jdbc.sql(
            "UPDATE product.deliverable SET quantity_accepted = ?, status = ?, updated_by = ?,"
                + " updated_at = ?, row_version = row_version + 1 WHERE period_bucket = ? AND"
                + " tenant_id = ? AND id = ? AND (quantity_accepted <> ? OR status <> ?)")
        .params(
            rollup.quantityAccepted(),
            rollup.status().name(),
            actorId,
            ts(at),
            periodBucket,
            tenant.value(),
            deliverableId,
            rollup.quantityAccepted(),
            rollup.status().name())
        .update();
  }

  private static EvidenceVersion version(ResultSet rs) throws SQLException {
    return new EvidenceVersion(
        rs.getObject("id", UUID.class),
        rs.getObject("evidence_id", UUID.class),
        rs.getObject("deliverable_id", UUID.class),
        rs.getObject("task_id", UUID.class),
        rs.getInt("version_no"),
        EvidenceStatus.valueOf(rs.getString("status")),
        rs.getObject("object_id", UUID.class),
        rs.getBytes("content_sha256"),
        rs.getString("scan_status"),
        rs.getBoolean("ai_prefill"),
        instant(rs.getTimestamp("user_confirmed_at")),
        rs.getObject("submitted_by", UUID.class),
        instant(rs.getTimestamp("submitted_at")),
        instant(rs.getTimestamp("accepted_at")),
        rs.getString("note"),
        rs.getObject("excluded_period_id", UUID.class),
        rs.getLong("row_version"));
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
