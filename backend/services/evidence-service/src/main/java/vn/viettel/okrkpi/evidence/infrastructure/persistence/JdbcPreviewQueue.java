package vn.viettel.okrkpi.evidence.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.evidence.application.PreviewQueue;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Claim;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Due;
import vn.viettel.okrkpi.evidence.application.StoredFiles;
import vn.viettel.okrkpi.evidence.domain.PageSize;

/** Hàng đợi dựng xem trước trên các cột {@code preview_*} của {@code evidence.object_version}. */
public class JdbcPreviewQueue implements PreviewQueue {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final JdbcClient jdbc;
  private final StoredFiles files;

  public JdbcPreviewQueue(JdbcClient jdbc, StoredFiles files) {
    this.jdbc = jdbc;
    this.files = files;
  }

  @Override
  public List<Due> due(int limit) {
    return jdbc.sql("SELECT id, tenant_id FROM evidence.fn_ban_cho_xem_truoc(?)")
        .param(limit)
        .query((rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
        .list();
  }

  @Override
  public Optional<Claim> claim(UUID versionId, Duration lease) {
    return jdbc.sql(
            "UPDATE evidence.object_version SET preview_attempts = preview_attempts + 1,"
                + " preview_next_at = now() + make_interval(secs => ?)"
                + " WHERE id = ? AND preview_status = 'DANG_TAO' AND preview_next_at <= now()"
                + " RETURNING object_meta_id, version_no, preview_attempts")
        .params(lease.toSeconds(), versionId)
        .query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3)})
        .optional()
        .flatMap(
            c -> files.find((UUID) c[0], (Integer) c[1]).map(f -> new Claim(f, (Integer) c[2])));
  }

  @Override
  public void retryAt(UUID versionId, Instant at) {
    jdbc.sql(
            "UPDATE evidence.object_version SET preview_next_at = ? WHERE id = ?"
                + " AND preview_status = 'DANG_TAO'")
        .params(Timestamp.from(at), versionId)
        .update();
  }

  @Override
  public boolean ready(UUID versionId, List<PageSize> pages) {
    List<int[]> sizes = pages.stream().map(p -> new int[] {p.width(), p.height()}).toList();
    try {
      return jdbc.sql(
                  "UPDATE evidence.object_version SET preview_status = 'SAN_SANG',"
                      + " preview_pages = ?::jsonb WHERE id = ? AND preview_status = 'DANG_TAO'")
              .params(JSON.writeValueAsString(sizes), versionId)
              .update()
          == 1;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public boolean failed(UUID versionId) {
    return jdbc.sql(
                "UPDATE evidence.object_version SET preview_status = 'LOI' WHERE id = ?"
                    + " AND preview_status = 'DANG_TAO'")
            .param(versionId)
            .update()
        == 1;
  }

  @Override
  public Optional<State> state(UUID objectId, Integer versionNo) {
    return files
        .find(objectId, versionNo)
        .map(
            f ->
                jdbc.sql(
                        "SELECT preview_status, preview_pages::text FROM evidence.object_version"
                            + " WHERE id = ?")
                    .param(f.versionId())
                    .query((rs, i) -> new State(f, rs.getString(1), pages(rs.getString(2))))
                    .single());
  }

  private static List<PageSize> pages(String json) {
    if (json == null) {
      return List.of();
    }
    try {
      List<PageSize> out = new ArrayList<>();
      for (int[] p : JSON.readValue(json, int[][].class)) {
        out.add(new PageSize(p[0], p[1]));
      }
      return out;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
