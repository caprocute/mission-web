package vn.viettel.okrkpi.evidence.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.evidence.application.UploadSessions;
import vn.viettel.okrkpi.evidence.domain.FileKind;
import vn.viettel.okrkpi.evidence.domain.UploadSession;

/** {@code evidence.upload_session} qua JdbcClient; RLS giới hạn theo tenant của giao dịch. */
public class JdbcUploadSessions implements UploadSessions {

  private static final String COLUMNS =
      "id, tenant_id, user_id, rgw_upload_id, declared_sha256, declared_size, part_size,"
          + " parts_done, expires_at, status, media_type, classification, owner_module,"
          + " owner_ref_id, file_name_enc, dek_id";

  private final JdbcClient jdbc;

  public JdbcUploadSessions(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(UploadSession s) {
    jdbc.sql(
            "INSERT INTO evidence.upload_session (id, tenant_id, created_by, updated_by, user_id,"
                + " rgw_upload_id, declared_sha256, declared_size, part_size, expires_at, status,"
                + " media_type, classification, owner_module, owner_ref_id, file_name_enc, dek_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            s.id(),
            s.tenantId(),
            s.userId(),
            s.userId(),
            s.userId(),
            s.rgwUploadId(),
            s.declaredSha256(),
            s.declaredSize(),
            s.partSize(),
            Timestamp.from(s.expiresAt()),
            s.status().name(),
            s.kind().mediaType(),
            s.classification(),
            s.ownerModule(),
            s.ownerRefId(),
            s.fileNameEnc(),
            s.dekId())
        .update();
  }

  @Override
  public Optional<UploadSession> find(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM evidence.upload_session WHERE id = ?")
        .param(id)
        .query(JdbcUploadSessions::map)
        .optional();
  }

  @Override
  public void updatePartsDone(UUID id, int partsDone, UUID actor) {
    jdbc.sql(
            "UPDATE evidence.upload_session SET parts_done = ?, updated_at = now(), updated_by = ?,"
                + " row_version = row_version + 1 WHERE id = ? AND status = 'MO'")
        .params(partsDone, actor, id)
        .update();
  }

  @Override
  public boolean close(UUID id, UploadSession.Status to, UUID actor) {
    return jdbc.sql(
                "UPDATE evidence.upload_session SET status = ?, updated_at = now(), updated_by = ?,"
                    + " row_version = row_version + 1 WHERE id = ? AND status = 'MO'")
            .params(to.name(), actor, id)
            .update()
        == 1;
  }

  @Override
  public List<Expired> expired(int limit) {
    return jdbc.sql("SELECT id, tenant_id FROM evidence.fn_phien_tai_len_het_han(?)")
        .param(limit)
        .query(
            (rs, i) ->
                new Expired(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)))
        .list();
  }

  private static UploadSession map(ResultSet rs, int i) throws SQLException {
    return new UploadSession(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("rgw_upload_id"),
        rs.getBytes("declared_sha256"),
        rs.getLong("declared_size"),
        rs.getInt("part_size"),
        rs.getInt("parts_done"),
        rs.getTimestamp("expires_at").toInstant(),
        UploadSession.Status.valueOf(rs.getString("status")),
        FileKind.ofMediaType(rs.getString("media_type")).orElseThrow(),
        rs.getString("classification"),
        rs.getString("owner_module"),
        rs.getObject("owner_ref_id", UUID.class),
        rs.getBytes("file_name_enc"),
        rs.getObject("dek_id", UUID.class));
  }
}
