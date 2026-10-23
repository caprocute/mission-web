package vn.viettel.okrkpi.evidence.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.evidence.application.OcrQueue;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Claim;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Due;
import vn.viettel.okrkpi.evidence.application.StoredFiles;

/** Hàng đợi đọc chữ trên {@code evidence.object_version} và {@code evidence.ocr_text} (V5). */
public class JdbcOcrQueue implements OcrQueue {

  private final JdbcClient jdbc;
  private final StoredFiles files;

  public JdbcOcrQueue(JdbcClient jdbc, StoredFiles files) {
    this.jdbc = jdbc;
    this.files = files;
  }

  @Override
  public List<Due> due(int limit) {
    return jdbc.sql("SELECT id, tenant_id FROM evidence.fn_ban_cho_ocr(?)")
        .param(limit)
        .query((rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
        .list();
  }

  @Override
  public Optional<Claim> claim(UUID versionId, Duration lease) {
    return jdbc.sql(
            "UPDATE evidence.object_version SET ocr_attempts = ocr_attempts + 1,"
                + " ocr_next_at = now() + make_interval(secs => ?)"
                + " WHERE id = ? AND ocr_status = 'CHO_DOC' AND ocr_next_at <= now()"
                + " RETURNING object_meta_id, version_no, ocr_attempts")
        .params(lease.toSeconds(), versionId)
        .query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3)})
        .optional()
        .flatMap(
            c -> files.find((UUID) c[0], (Integer) c[1]).map(f -> new Claim(f, (Integer) c[2])));
  }

  @Override
  public void retryAt(UUID versionId, Instant at) {
    jdbc.sql(
            "UPDATE evidence.object_version SET ocr_next_at = ? WHERE id = ?"
                + " AND ocr_status = 'CHO_DOC'")
        .params(Timestamp.from(at), versionId)
        .update();
  }

  @Override
  public boolean done(UUID versionId, Text text) {
    if (!transition(versionId, "DA_DOC")) {
      return false;
    }
    jdbc.sql(
            "INSERT INTO evidence.ocr_text (object_version_id, text_enc, dek_id, model_id,"
                + " page_count, processed_at) VALUES (?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (object_version_id) DO NOTHING")
        .params(
            versionId,
            text.textEnc(),
            text.dekId(),
            text.modelId().length() > 64 ? text.modelId().substring(0, 64) : text.modelId(),
            text.pageCount(),
            Timestamp.from(text.processedAt()))
        .update();
    return true;
  }

  @Override
  public boolean unreadable(UUID versionId) {
    return transition(versionId, "KHONG_DOC_DUOC");
  }

  @Override
  public boolean failed(UUID versionId) {
    return transition(versionId, "LOI");
  }

  @Override
  public Optional<String> status(UUID versionId) {
    return jdbc.sql("SELECT ocr_status FROM evidence.object_version WHERE id = ?")
        .param(versionId)
        .query(String.class)
        .optional();
  }

  private boolean transition(UUID versionId, String to) {
    return jdbc.sql(
                "UPDATE evidence.object_version SET ocr_status = ? WHERE id = ?"
                    + " AND ocr_status = 'CHO_DOC'")
            .params(to, versionId)
            .update()
        == 1;
  }
}
