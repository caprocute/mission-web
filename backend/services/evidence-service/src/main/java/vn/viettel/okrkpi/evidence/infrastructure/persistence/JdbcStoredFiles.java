package vn.viettel.okrkpi.evidence.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.evidence.application.StoredFiles;
import vn.viettel.okrkpi.evidence.domain.StoredFile;

/** {@code evidence.object_meta} và {@code evidence.object_version} qua JdbcClient (RLS). */
public class JdbcStoredFiles implements StoredFiles {

  private final JdbcClient jdbc;

  public JdbcStoredFiles(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(StoredFile f, byte[] fileNameEnc, UUID dekId) {
    jdbc.sql(
            "INSERT INTO evidence.object_meta (id, tenant_id, created_at, created_by, updated_at,"
                + " updated_by, owner_module, owner_ref_id, file_name_enc, dek_id, media_type,"
                + " classification, retention_class) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " 'MINH_CHUNG')")
        .params(
            f.objectId(),
            f.tenantId(),
            Timestamp.from(f.createdAt()),
            f.createdBy(),
            Timestamp.from(f.createdAt()),
            f.createdBy(),
            f.ownerModule(),
            f.ownerRefId(),
            fileNameEnc,
            dekId,
            f.mediaType(),
            f.classification())
        .update();
    jdbc.sql(
            "INSERT INTO evidence.object_version (id, tenant_id, object_meta_id, version_no,"
                + " sha256, size_bytes, bucket, object_key, ceph_version_id, lock_mode, lock_until,"
                + " scan_status, created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " 'COMPLIANCE', ?, ?, ?, ?)")
        .params(
            f.versionId(),
            f.tenantId(),
            f.objectId(),
            f.versionNo(),
            f.sha256(),
            f.sizeBytes(),
            f.bucket(),
            f.objectKey(),
            f.cephVersionId(),
            Timestamp.from(f.lockUntil()),
            f.scanStatus(),
            Timestamp.from(f.createdAt()),
            f.createdBy())
        .update();
    jdbc.sql("UPDATE evidence.object_meta SET current_version_id = ? WHERE id = ?")
        .params(f.versionId(), f.objectId())
        .update();
  }

  @Override
  public Optional<StoredFile> find(UUID objectId, Integer versionNo) {
    String which =
        versionNo == null ? "v.id = m.current_version_id" : "v.version_no = " + versionNo;
    return jdbc.sql(
            "SELECT m.id AS object_id, v.id AS version_id, v.version_no, m.tenant_id,"
                + " v.created_by, m.owner_module, m.owner_ref_id, m.media_type, m.classification,"
                + " v.size_bytes, v.sha256, v.bucket, v.object_key, v.ceph_version_id,"
                + " v.lock_until, v.scan_status, v.created_at"
                + " FROM evidence.object_meta m JOIN evidence.object_version v"
                + " ON v.tenant_id = m.tenant_id AND v.object_meta_id = m.id"
                + " WHERE m.id = ? AND "
                + which)
        .param(objectId)
        .query(JdbcStoredFiles::map)
        .optional();
  }

  private static StoredFile map(ResultSet rs, int i) throws SQLException {
    Timestamp lock = rs.getTimestamp("lock_until");
    return new StoredFile(
        rs.getObject("object_id", UUID.class),
        rs.getObject("version_id", UUID.class),
        rs.getInt("version_no"),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("created_by", UUID.class),
        rs.getString("owner_module"),
        rs.getObject("owner_ref_id", UUID.class),
        rs.getString("media_type"),
        rs.getString("classification"),
        rs.getLong("size_bytes"),
        rs.getBytes("sha256"),
        rs.getString("bucket"),
        rs.getString("object_key"),
        rs.getString("ceph_version_id"),
        lock == null ? null : lock.toInstant(),
        rs.getString("scan_status"),
        rs.getTimestamp("created_at").toInstant());
  }
}
