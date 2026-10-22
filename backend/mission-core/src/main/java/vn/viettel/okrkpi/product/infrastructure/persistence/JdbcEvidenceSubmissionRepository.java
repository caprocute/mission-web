package vn.viettel.okrkpi.product.infrastructure.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link EvidenceSubmissionRepository} trên {@code productDataSource} (vai trò {@code svc_product},
 * RLS).
 */
public class JdbcEvidenceSubmissionRepository implements EvidenceSubmissionRepository {

  private static final String DELIVERABLE_COLUMNS =
      "id, period_id, period_bucket, task_id, name, deliverable_type, unit, quantity_planned,"
          + " quantity_accepted, status, row_version";

  private static final String EVIDENCE_SELECT =
      "SELECT e.id, e.period_id, e.period_bucket, e.deliverable_id, e.task_id, e.title,"
          + " e.source_type, e.external_doc_ref, e.row_version, cv.version_no, cv.status AS"
          + " cv_status, cv.is_duplicate_suspect, cv.submitted_by, cv.submitted_at FROM"
          + " product.evidence e LEFT JOIN product.evidence_version cv ON cv.period_bucket ="
          + " e.period_bucket AND cv.tenant_id = e.tenant_id AND cv.id = e.current_version_id";

  private static final String VERSION_COLUMNS =
      "id, evidence_id, task_id, period_id, period_bucket, version_no, status, source,"
          + " source_doc_ref, scan_status, is_duplicate_suspect, has_e2ee, ai_prefill,"
          + " ai_suggestion_id, user_confirmed_at, doc_number, doc_date, doc_summary, note,"
          + " submitted_by, submitted_at, attested_at, accepted_at, excluded_period_id,"
          + " row_version";

  private static final String OBJECT_COLUMNS =
      "o.id AS o_id, o.object_version AS o_version, o.content_sha256 AS o_sha, o.size_bytes AS"
          + " o_size, o.media_type AS o_media, o.has_e2ee AS o_e2ee, o.scan_status AS o_scan,"
          + " o.stored_at AS o_stored";

  private final JdbcClient jdbc;

  public JdbcEvidenceSubmissionRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  // ---------------------------------------------------------------- deliverable

  @Override
  public void insertDeliverable(TenantId tenant, NewDeliverable d, UUID actorId, Instant at) {
    jdbc.sql(
            "INSERT INTO product.deliverable (id, tenant_id, created_at, created_by, updated_at,"
                + " updated_by, period_id, period_bucket, task_id, name, deliverable_type, unit,"
                + " quantity_planned) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            d.id(),
            tenant.value(),
            ts(at),
            actorId,
            ts(at),
            actorId,
            d.periodId(),
            d.periodBucket(),
            d.taskId(),
            d.name(),
            d.deliverableType(),
            d.unit(),
            d.quantityPlanned())
        .update();
  }

  @Override
  public Optional<DeliverableView> findDeliverable(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + DELIVERABLE_COLUMNS
                + " FROM product.deliverable WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query((rs, i) -> deliverable(rs))
        .optional();
  }

  @Override
  public List<DeliverableView> listDeliverables(
      TenantId tenant, UUID taskId, UUID afterId, int limit) {
    return jdbc.sql(
            "SELECT "
                + DELIVERABLE_COLUMNS
                + " FROM product.deliverable WHERE tenant_id = ? AND task_id = ? AND (?::uuid IS"
                + " NULL OR id > ?) ORDER BY id LIMIT ?")
        .params(tenant.value(), taskId, afterId, afterId, limit)
        .query((rs, i) -> deliverable(rs))
        .list();
  }

  // ---------------------------------------------------------------- evidence

  @Override
  public void insertEvidence(TenantId tenant, NewEvidence e, UUID actorId, Instant at) {
    jdbc.sql(
            "INSERT INTO product.evidence (id, tenant_id, created_at, created_by, updated_at,"
                + " updated_by, period_id, period_bucket, deliverable_id, task_id, title,"
                + " source_type, external_doc_ref) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            e.id(),
            tenant.value(),
            ts(at),
            actorId,
            ts(at),
            actorId,
            e.periodId(),
            e.periodBucket(),
            e.deliverableId(),
            e.taskId(),
            e.title(),
            e.sourceType(),
            e.externalDocRef())
        .update();
  }

  @Override
  public Optional<EvidenceView> findEvidence(TenantId tenant, UUID id) {
    return jdbc.sql(EVIDENCE_SELECT + " WHERE e.tenant_id = ? AND e.id = ?")
        .params(tenant.value(), id)
        .query((rs, i) -> evidence(rs))
        .optional();
  }

  @Override
  public Optional<EvidenceView> lockEvidence(TenantId tenant, UUID id) {
    return jdbc.sql(EVIDENCE_SELECT + " WHERE e.tenant_id = ? AND e.id = ? FOR UPDATE OF e")
        .params(tenant.value(), id)
        .query((rs, i) -> evidence(rs))
        .optional();
  }

  @Override
  public List<EvidenceView> listEvidences(
      TenantId tenant, EvidenceFilter filter, UUID afterId, int limit) {
    StringBuilder sql = new StringBuilder(EVIDENCE_SELECT).append(" WHERE e.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant.value());
    if (filter.taskId() != null) {
      sql.append(" AND e.task_id = ?");
      params.add(filter.taskId());
    }
    if (filter.deliverableId() != null) {
      sql.append(" AND e.deliverable_id = ?");
      params.add(filter.deliverableId());
    }
    if (filter.periodId() != null) {
      sql.append(" AND e.period_id = ?");
      params.add(filter.periodId());
    }
    if (filter.statuses() != null && !filter.statuses().isEmpty()) {
      sql.append(" AND cv.status = ANY(?::varchar[])");
      params.add(filter.statuses().toArray(new String[0]));
    }
    if (filter.submitter() != null) {
      sql.append(" AND (e.created_by = ? OR cv.submitted_by = ?)");
      params.add(filter.submitter());
      params.add(filter.submitter());
    }
    if (filter.reviewer() != null) {
      sql.append(" AND e.reviewer_id = ?");
      params.add(filter.reviewer());
    }
    if (afterId != null) {
      sql.append(" AND e.id > ?");
      params.add(afterId);
    }
    sql.append(" ORDER BY e.id LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query((rs, i) -> evidence(rs)).list();
  }

  @Override
  public List<VersionSummary> versionSummaries(TenantId tenant, int periodBucket, UUID evidenceId) {
    return jdbc.sql(
            "SELECT version_no, status, submitted_at, accepted_at FROM product.evidence_version"
                + " WHERE period_bucket = ? AND tenant_id = ? AND evidence_id = ? ORDER BY"
                + " version_no")
        .params(periodBucket, tenant.value(), evidenceId)
        .query(
            (rs, i) ->
                new VersionSummary(
                    rs.getInt("version_no"),
                    rs.getString("status"),
                    instant(rs.getTimestamp("submitted_at")),
                    instant(rs.getTimestamp("accepted_at"))))
        .list();
  }

  // ---------------------------------------------------------------- version

  @Override
  public Optional<VersionView> latestVersion(TenantId tenant, int periodBucket, UUID evidenceId) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM product.evidence_version WHERE period_bucket = ? AND tenant_id = ? AND"
                + " evidence_id = ? ORDER BY version_no DESC LIMIT 1")
        .params(periodBucket, tenant.value(), evidenceId)
        .query((rs, i) -> version(rs))
        .optional();
  }

  @Override
  public Optional<VersionView> findVersion(
      TenantId tenant, int periodBucket, UUID evidenceId, int versionNo) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM product.evidence_version WHERE period_bucket = ? AND tenant_id = ? AND"
                + " evidence_id = ? AND version_no = ?")
        .params(periodBucket, tenant.value(), evidenceId, versionNo)
        .query((rs, i) -> version(rs))
        .optional();
  }

  @Override
  public void insertVersion(
      TenantId tenant,
      EvidenceView evidence,
      UUID versionId,
      int versionNo,
      String source,
      UUID actorId,
      Instant at) {
    jdbc.sql(
            "INSERT INTO product.evidence_version (id, tenant_id, created_at, created_by,"
                + " updated_at, updated_by, period_id, period_bucket, evidence_id, task_id,"
                + " version_no, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            versionId,
            tenant.value(),
            ts(at),
            actorId,
            ts(at),
            actorId,
            evidence.periodId(),
            evidence.periodBucket(),
            evidence.id(),
            evidence.taskId(),
            versionNo,
            source)
        .update();
    jdbc.sql(
            "UPDATE product.evidence SET current_version_id = ?, current_status = 'NHAP',"
                + " updated_by = ?, updated_at = ?, row_version = row_version + 1 WHERE"
                + " period_bucket = ? AND tenant_id = ? AND id = ?")
        .params(versionId, actorId, ts(at), evidence.periodBucket(), tenant.value(), evidence.id())
        .update();
  }

  @Override
  public void writeDraftDetails(TenantId tenant, int periodBucket, UUID versionId, DraftDetails d) {
    jdbc.sql(
            "UPDATE product.evidence_version SET source = ?, source_doc_ref = ?,"
                + " ai_suggestion_id = ?, doc_number = ?, doc_date = ?, doc_summary = ?,"
                + " size_bytes = ?, media_type = ?, has_e2ee = ?, is_duplicate_suspect = ?,"
                + " duplicate_of_id = ?, reuse_justification = ? WHERE period_bucket = ? AND"
                + " tenant_id = ? AND id = ? AND status = 'NHAP'")
        .params(
            d.source(),
            d.sourceDocRef(),
            d.aiSuggestionId(),
            d.docNumber(),
            d.docDate() == null ? null : Date.valueOf(d.docDate()),
            d.docSummary(),
            d.sizeBytes(),
            d.mediaType(),
            d.hasE2ee(),
            d.duplicateSuspect(),
            d.duplicateOfId(),
            d.reuseJustification(),
            periodBucket,
            tenant.value(),
            versionId)
        .update();
  }

  @Override
  public void replaceFiles(
      TenantId tenant, VersionView version, List<FileRow> files, UUID actorId, Instant at) {
    jdbc.sql(
            "DELETE FROM product.evidence_version_file WHERE period_bucket = ? AND tenant_id = ?"
                + " AND evidence_version_id = ?")
        .params(version.periodBucket(), tenant.value(), version.id())
        .update();
    for (FileRow f : files) {
      jdbc.sql(
              "INSERT INTO product.evidence_version_file (id, tenant_id, created_at, created_by,"
                  + " updated_at, updated_by, period_id, period_bucket, evidence_version_id,"
                  + " task_id, ordinal, object_id, object_version, content_sha256, scan_status,"
                  + " is_duplicate_suspect, duplicate_of_id, reuse_justification) VALUES (?, ?,"
                  + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
          .params(
              UUID.randomUUID(),
              tenant.value(),
              ts(at),
              actorId,
              ts(at),
              actorId,
              version.periodId(),
              version.periodBucket(),
              version.id(),
              version.taskId(),
              f.ordinal(),
              f.objectId(),
              f.objectVersion(),
              f.contentSha256(),
              f.scanStatus(),
              f.duplicateOfVersionId() != null,
              f.duplicateOfVersionId(),
              f.reuseJustification())
          .update();
    }
  }

  @Override
  public List<FileView> filesOf(TenantId tenant, int periodBucket, UUID versionId) {
    return jdbc.sql(
            "SELECT f.object_id, f.ordinal, f.object_version, f.content_sha256, f.scan_status,"
                + " f.duplicate_of_id, f.reuse_justification, "
                + OBJECT_COLUMNS
                + " FROM product.evidence_version_file f LEFT JOIN product.evidence_object o ON"
                + " o.tenant_id = f.tenant_id AND o.id = f.object_id WHERE f.period_bucket = ? AND"
                + " f.tenant_id = ? AND f.evidence_version_id = ? ORDER BY f.ordinal")
        .params(periodBucket, tenant.value(), versionId)
        .query(
            (rs, i) ->
                new FileView(
                    new FileRow(
                        rs.getObject("object_id", UUID.class),
                        rs.getInt("ordinal"),
                        rs.getInt("object_version"),
                        rs.getBytes("content_sha256"),
                        rs.getString("scan_status"),
                        rs.getObject("duplicate_of_id", UUID.class),
                        rs.getString("reuse_justification")),
                    rs.getObject("o_id") == null ? null : object(rs)))
        .list();
  }

  @Override
  public Map<UUID, StoredObject> storedObjects(TenantId tenant, Collection<UUID> objectIds) {
    Map<UUID, StoredObject> out = new LinkedHashMap<>();
    if (objectIds.isEmpty()) {
      return out;
    }
    jdbc.sql(
            "SELECT "
                + OBJECT_COLUMNS
                + " FROM product.evidence_object o WHERE o.tenant_id = ? AND o.id ="
                + " ANY(?::uuid[])")
        .params(tenant.value(), objectIds.stream().map(UUID::toString).toArray(String[]::new))
        .query((rs, i) -> object(rs))
        .list()
        .forEach(o -> out.put(o.id(), o));
    return out;
  }

  @Override
  public Optional<Duplicate> firstDuplicate(
      TenantId tenant, byte[] contentSha256, UUID excludeTaskId) {
    return jdbc.sql(
            "SELECT v.id, v.task_id, v.submitted_at FROM product.evidence_version_file f JOIN"
                + " product.evidence_version v ON v.period_bucket = f.period_bucket AND"
                + " v.tenant_id = f.tenant_id AND v.id = f.evidence_version_id WHERE f.tenant_id"
                + " = ? AND f.content_sha256 = ? AND f.task_id <> ? AND v.status <> 'NHAP' ORDER"
                + " BY v.submitted_at, v.id LIMIT 1")
        .params(tenant.value(), contentSha256, excludeTaskId)
        .query((rs, i) -> duplicate(rs))
        .optional();
  }

  @Override
  public Optional<Duplicate> versionRef(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT id, task_id, submitted_at FROM product.evidence_version WHERE tenant_id = ?"
                + " AND id = ?")
        .params(tenant.value(), versionId)
        .query((rs, i) -> duplicate(rs))
        .optional();
  }

  @Override
  public void markAttested(TenantId tenant, int periodBucket, UUID versionId, Instant at) {
    jdbc.sql(
            "UPDATE product.evidence_version SET attested_at = ? WHERE period_bucket = ? AND"
                + " tenant_id = ? AND id = ? AND status = 'NHAP'")
        .params(ts(at), periodBucket, tenant.value(), versionId)
        .update();
  }

  @Override
  public Set<UUID> confirmedOf(TenantId tenant, UUID personId, Set<UUID> ids) {
    return new HashSet<>(
        jdbc.sql(
                "SELECT DISTINCT v.evidence_id FROM product.evidence_version v WHERE v.tenant_id = ?"
                    + " AND v.evidence_id = ANY(?::uuid[]) AND v.status = 'DA_XAC_NHAN' AND"
                    + " v.excluded_period_id IS NULL AND v.submitted_by = ?")
            .params(tenant.value(), ids.toArray(new UUID[0]), personId)
            .query(UUID.class)
            .list());
  }

  @Override
  public Set<UUID> acceptorsOf(TenantId tenant, UUID periodId, UUID personId) {
    return new HashSet<>(
        jdbc.sql(
                "SELECT DISTINCT a.reviewer_id FROM product.acceptance a JOIN"
                    + " product.evidence_version v ON v.period_bucket = a.period_bucket AND"
                    + " v.tenant_id = a.tenant_id AND v.id = a.evidence_version_id WHERE"
                    + " a.tenant_id = ? AND a.period_id = ? AND v.submitted_by = ?")
            .params(tenant.value(), periodId, personId)
            .query(UUID.class)
            .list());
  }

  @Override
  public void markReviewer(TenantId tenant, int periodBucket, UUID evidenceId, UUID reviewerId) {
    jdbc.sql(
            "UPDATE product.evidence SET reviewer_id = ? WHERE period_bucket = ? AND tenant_id = ?"
                + " AND id = ?")
        .params(reviewerId, periodBucket, tenant.value(), evidenceId)
        .update();
  }

  // ---------------------------------------------------------------- bản chiếu SVC-08

  @Override
  public boolean upsertStoredObject(TenantId tenant, StoredObject o, UUID actorId, Instant at) {
    return jdbc.sql(
            "INSERT INTO product.evidence_object AS t (id, tenant_id, created_at, created_by,"
                + " updated_at, updated_by, object_version, content_sha256, size_bytes,"
                + " media_type, has_e2ee, scan_status, stored_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, 'CHO_QUET', ?) ON CONFLICT (tenant_id, id) DO UPDATE SET"
                + " content_sha256 = EXCLUDED.content_sha256, size_bytes = EXCLUDED.size_bytes,"
                + " media_type = EXCLUDED.media_type, has_e2ee = EXCLUDED.has_e2ee, stored_at ="
                + " EXCLUDED.stored_at, scan_status = CASE WHEN t.object_version ="
                + " EXCLUDED.object_version THEN t.scan_status ELSE 'CHO_QUET' END,"
                + " object_version = EXCLUDED.object_version, updated_at = EXCLUDED.updated_at,"
                + " updated_by = EXCLUDED.updated_by, row_version = t.row_version + 1 WHERE"
                + " t.object_version < EXCLUDED.object_version OR (t.stored_at IS NULL AND"
                + " t.object_version = EXCLUDED.object_version) RETURNING t.id")
        .params(
            o.id(),
            tenant.value(),
            ts(at),
            actorId,
            ts(at),
            actorId,
            o.objectVersion(),
            o.contentSha256(),
            o.sizeBytes(),
            o.mediaType(),
            o.hasE2ee(),
            ts(o.storedAt()))
        .query(UUID.class)
        .optional()
        .isPresent();
  }

  @Override
  public boolean applyScanVerdict(
      TenantId tenant,
      UUID objectId,
      int objectVersion,
      String scanStatus,
      UUID actorId,
      Instant at) {
    return jdbc.sql(
            "INSERT INTO product.evidence_object AS t (id, tenant_id, created_at, created_by,"
                + " updated_at, updated_by, object_version, scan_status) VALUES (?, ?, ?, ?, ?, ?,"
                + " ?, ?) ON CONFLICT (tenant_id, id) DO UPDATE SET scan_status ="
                + " EXCLUDED.scan_status, updated_at = EXCLUDED.updated_at, updated_by ="
                + " EXCLUDED.updated_by, row_version = t.row_version + 1 WHERE t.object_version ="
                + " EXCLUDED.object_version AND t.scan_status <> EXCLUDED.scan_status RETURNING"
                + " t.id")
        .params(
            objectId, tenant.value(), ts(at), actorId, ts(at), actorId, objectVersion, scanStatus)
        .query(UUID.class)
        .optional()
        .isPresent();
  }

  @Override
  public List<DraftRef> syncDraftFileScan(TenantId tenant, UUID objectId) {
    Set<DraftRef> refs =
        new LinkedHashSet<>(
            jdbc.sql(
                    "UPDATE product.evidence_version_file f SET scan_status = o.scan_status,"
                        + " updated_at = now(), row_version = f.row_version + 1 FROM"
                        + " product.evidence_object o, product.evidence_version v WHERE"
                        + " o.tenant_id = f.tenant_id AND o.id = f.object_id AND o.object_version"
                        + " = f.object_version AND f.scan_status <> o.scan_status AND"
                        + " v.period_bucket = f.period_bucket AND v.tenant_id = f.tenant_id AND"
                        + " v.id = f.evidence_version_id AND v.status = 'NHAP' AND f.tenant_id = ?"
                        + " AND f.object_id = ? RETURNING f.evidence_version_id, f.period_bucket")
                .params(tenant.value(), objectId)
                .query(
                    (rs, i) ->
                        new DraftRef(
                            rs.getObject("evidence_version_id", UUID.class),
                            rs.getInt("period_bucket")))
                .list());
    return List.copyOf(refs);
  }

  @Override
  public void writeDraftScan(TenantId tenant, int periodBucket, UUID versionId, String scanStatus) {
    jdbc.sql(
            "UPDATE product.evidence_version SET scan_status = ? WHERE period_bucket = ? AND"
                + " tenant_id = ? AND id = ? AND status = 'NHAP'")
        .params(scanStatus, periodBucket, tenant.value(), versionId)
        .update();
  }

  // ---------------------------------------------------------------- ánh xạ

  private static DeliverableView deliverable(ResultSet rs) throws SQLException {
    return new DeliverableView(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getObject("task_id", UUID.class),
        rs.getString("name"),
        rs.getString("deliverable_type"),
        rs.getString("unit"),
        rs.getBigDecimal("quantity_planned"),
        rs.getBigDecimal("quantity_accepted"),
        rs.getString("status"),
        rs.getLong("row_version"));
  }

  private static EvidenceView evidence(ResultSet rs) throws SQLException {
    int no = rs.getInt("version_no");
    boolean hasVersion = !rs.wasNull();
    return new EvidenceView(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getObject("deliverable_id", UUID.class),
        rs.getObject("task_id", UUID.class),
        rs.getString("title"),
        rs.getString("source_type"),
        rs.getString("external_doc_ref"),
        hasVersion ? no : null,
        hasVersion ? rs.getString("cv_status") : null,
        rs.getBoolean("is_duplicate_suspect"),
        rs.getObject("submitted_by", UUID.class),
        instant(rs.getTimestamp("submitted_at")),
        rs.getLong("row_version"));
  }

  private static VersionView version(ResultSet rs) throws SQLException {
    Date docDate = rs.getDate("doc_date");
    return new VersionView(
        rs.getObject("id", UUID.class),
        rs.getObject("evidence_id", UUID.class),
        rs.getObject("task_id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getInt("version_no"),
        rs.getString("status"),
        rs.getString("source"),
        rs.getString("source_doc_ref"),
        rs.getString("scan_status"),
        rs.getBoolean("is_duplicate_suspect"),
        rs.getBoolean("has_e2ee"),
        rs.getBoolean("ai_prefill"),
        rs.getObject("ai_suggestion_id", UUID.class),
        instant(rs.getTimestamp("user_confirmed_at")),
        rs.getString("doc_number"),
        docDate == null ? null : docDate.toLocalDate(),
        rs.getString("doc_summary"),
        rs.getString("note"),
        rs.getObject("submitted_by", UUID.class),
        instant(rs.getTimestamp("submitted_at")),
        instant(rs.getTimestamp("attested_at")),
        instant(rs.getTimestamp("accepted_at")),
        rs.getObject("excluded_period_id", UUID.class),
        rs.getLong("row_version"));
  }

  private static StoredObject object(ResultSet rs) throws SQLException {
    long size = rs.getLong("o_size");
    boolean sizeNull = rs.wasNull();
    return new StoredObject(
        rs.getObject("o_id", UUID.class),
        rs.getInt("o_version"),
        rs.getBytes("o_sha"),
        sizeNull ? null : size,
        rs.getString("o_media"),
        rs.getBoolean("o_e2ee"),
        rs.getString("o_scan"),
        instant(rs.getTimestamp("o_stored")));
  }

  private static Duplicate duplicate(ResultSet rs) throws SQLException {
    return new Duplicate(
        rs.getObject("id", UUID.class),
        rs.getObject("task_id", UUID.class),
        instant(rs.getTimestamp("submitted_at")));
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
