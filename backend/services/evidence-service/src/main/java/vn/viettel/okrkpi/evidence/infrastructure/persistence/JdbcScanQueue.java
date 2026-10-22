package vn.viettel.okrkpi.evidence.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.evidence.application.ScanQueue;
import vn.viettel.okrkpi.evidence.application.StoredFiles;
import vn.viettel.okrkpi.evidence.domain.ScanRecord;
import vn.viettel.okrkpi.evidence.domain.ScanVerdict;

/** Hàng đợi quét trên {@code evidence.object_version} và {@code evidence.scan_result} (RLS). */
public class JdbcScanQueue implements ScanQueue {

  private final JdbcClient jdbc;
  private final StoredFiles files;

  public JdbcScanQueue(JdbcClient jdbc, StoredFiles files) {
    this.jdbc = jdbc;
    this.files = files;
  }

  @Override
  public List<Due> due(int limit) {
    return jdbc.sql("SELECT id, tenant_id FROM evidence.fn_ban_cho_quet(?)")
        .param(limit)
        .query((rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
        .list();
  }

  @Override
  public Optional<Claim> claim(UUID versionId, Duration lease) {
    Optional<Object[]> claimed =
        jdbc.sql(
                "UPDATE evidence.object_version SET scan_attempts = scan_attempts + 1,"
                    + " scan_next_at = now() + make_interval(secs => ?)"
                    + " WHERE id = ? AND scan_status = 'CHO_QUET' AND scan_next_at <= now()"
                    + " RETURNING object_meta_id, version_no, scan_attempts")
            .params(lease.toSeconds(), versionId)
            .query(
                (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3)})
            .optional();
    return claimed.flatMap(
        c -> files.find((UUID) c[0], (Integer) c[1]).map(f -> new Claim(f, (Integer) c[2])));
  }

  @Override
  public void retryAt(UUID versionId, Instant at) {
    jdbc.sql(
            "UPDATE evidence.object_version SET scan_next_at = ? WHERE id = ?"
                + " AND scan_status = 'CHO_QUET'")
        .params(Timestamp.from(at), versionId)
        .update();
  }

  @Override
  public boolean record(ScanRecord r) {
    int moved =
        jdbc.sql(
                "UPDATE evidence.object_version SET scan_status = ? WHERE id = ?"
                    + " AND scan_status = 'CHO_QUET'")
            .params(r.verdict().scanStatus(), r.versionId())
            .update();
    if (moved == 0) {
      return false;
    }
    jdbc.sql(
            "INSERT INTO evidence.scan_result (id, object_version_id, engine, engine_version,"
                + " verdict, detail, scanned_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)")
        .params(
            r.id(),
            r.versionId(),
            r.engine(),
            r.engineVersion(),
            r.verdict().name(),
            r.detail(),
            Timestamp.from(r.scannedAt()))
        .update();
    return true;
  }

  @Override
  public Optional<Status> status(UUID objectId, Integer versionNo) {
    return files
        .find(objectId, versionNo)
        .map(
            f ->
                new Status(
                    f,
                    jdbc.sql(
                            "SELECT id, engine, engine_version, verdict, detail::text, scanned_at"
                                + " FROM evidence.scan_result WHERE object_version_id = ?"
                                + " ORDER BY scanned_at DESC, id DESC LIMIT 1")
                        .param(f.versionId())
                        .query(
                            (rs, i) ->
                                new ScanRecord(
                                    rs.getObject("id", UUID.class),
                                    f.versionId(),
                                    rs.getString("engine"),
                                    rs.getString("engine_version"),
                                    ScanVerdict.valueOf(rs.getString("verdict")),
                                    rs.getString("detail"),
                                    rs.getTimestamp("scanned_at").toInstant()))
                        .optional()));
  }

  @Override
  public void schedulePreview(UUID versionId) {
    jdbc.sql(
            "UPDATE evidence.object_version SET preview_status = 'DANG_TAO', preview_next_at = now()"
                + " WHERE id = ? AND preview_status IS NULL")
        .param(versionId)
        .update();
  }

  @Override
  public long pending() {
    return jdbc.sql("SELECT evidence.fn_dem_cho_quet()").query(Long.class).single();
  }
}
