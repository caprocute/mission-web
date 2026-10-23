package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageExportRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@link PackageExportRepository} trên {@code dossier.package_export} (CORE-06-08). */
public class JdbcPackageExportRepository implements PackageExportRepository {

  private static final String COLUMNS =
      "id, period_bucket, dossier_package_id, status, object_key, size_bytes, file_count,"
          + " error_code, created_by, updated_at, completed_at";

  private final JdbcClient jdbc;

  public JdbcPackageExportRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void insert(TenantId tenant, UUID id, int periodBucket, UUID packageId, UUID actor) {
    jdbc.sql(
            """
            INSERT INTO dossier.package_export (id, tenant_id, created_by, updated_by,
                period_bucket, dossier_package_id, status)
            VALUES (?, ?, ?, ?, ?, ?, 'CHO')
            """)
        .params(id, tenant.value(), actor, actor, periodBucket, packageId)
        .update();
  }

  @Override
  public Optional<ExportRow> find(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM dossier.package_export WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query(JdbcPackageExportRepository::row)
        .optional();
  }

  @Override
  public boolean claim(TenantId tenant, UUID id, Duration stale) {
    return jdbc.sql(
                "UPDATE dossier.package_export SET status = 'DANG_CHAY', updated_at = now(),"
                    + " row_version = row_version + 1 WHERE tenant_id = ? AND id = ?"
                    + " AND (status = 'CHO' OR status = 'DANG_CHAY'"
                    + " AND updated_at < now() - make_interval(secs => ?))")
            .params(tenant.value(), id, (double) stale.toSeconds())
            .update()
        == 1;
  }

  @Override
  public void complete(TenantId tenant, UUID id, String objectKey, long sizeBytes, int fileCount) {
    jdbc.sql(
            "UPDATE dossier.package_export SET status = 'HOAN_TAT', object_key = ?, size_bytes = ?,"
                + " file_count = ?, completed_at = now(), updated_at = now(),"
                + " row_version = row_version + 1 WHERE tenant_id = ? AND id = ?")
        .params(objectKey, sizeBytes, fileCount, tenant.value(), id)
        .update();
  }

  @Override
  public void fail(TenantId tenant, UUID id, String errorCode) {
    jdbc.sql(
            "UPDATE dossier.package_export SET status = 'THAT_BAI', error_code = ?,"
                + " completed_at = now(), updated_at = now(), row_version = row_version + 1"
                + " WHERE tenant_id = ? AND id = ?")
        .params(errorCode, tenant.value(), id)
        .update();
  }

  @Override
  public List<UUID> stranded(TenantId tenant, Duration waiting, Duration stale, int limit) {
    return jdbc.sql(
            "SELECT id FROM dossier.package_export WHERE tenant_id = ? AND (status = 'CHO'"
                + " AND updated_at < now() - make_interval(secs => ?) OR status = 'DANG_CHAY'"
                + " AND updated_at < now() - make_interval(secs => ?)) ORDER BY updated_at"
                + " LIMIT ?")
        .params(tenant.value(), (double) waiting.toSeconds(), (double) stale.toSeconds(), limit)
        .query(UUID.class)
        .list();
  }

  private static ExportRow row(ResultSet rs, int i) throws SQLException {
    long size = rs.getLong("size_bytes");
    Long sizeBytes = rs.wasNull() ? null : size;
    int files = rs.getInt("file_count");
    Integer fileCount = rs.wasNull() ? null : files;
    return new ExportRow(
        rs.getObject("id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getObject("dossier_package_id", UUID.class),
        rs.getString("status"),
        rs.getString("object_key"),
        sizeBytes,
        fileCount,
        rs.getString("error_code"),
        rs.getObject("created_by", UUID.class),
        instant(rs.getTimestamp("updated_at")),
        instant(rs.getTimestamp("completed_at")));
  }

  static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
