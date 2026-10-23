package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.docgen.domain.render.OutputKind;
import vn.viettel.okrkpi.docgen.domain.render.RenderJob;
import vn.viettel.okrkpi.docgen.domain.render.RenderJobRepository;
import vn.viettel.okrkpi.docgen.domain.render.RenderJobStatus;
import vn.viettel.okrkpi.docgen.domain.render.RenderOutput;

/** {@link RenderJobRepository} trên JDBC ({@code docgen.render_job}, {@code render_output}). */
public class JdbcRenderJobRepository implements RenderJobRepository {

  private final JdbcClient jdbc;

  public JdbcRenderJobRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<RenderJob> findById(UUID id) {
    return jdbc.sql("SELECT * FROM docgen.render_job WHERE id = ?")
        .param(id)
        .query((rs, i) -> job(rs))
        .optional();
  }

  @Override
  public Optional<RenderJob> lockForProcessing(UUID id) {
    return jdbc.sql("SELECT * FROM docgen.render_job WHERE id = ? FOR UPDATE SKIP LOCKED")
        .param(id)
        .query((rs, i) -> job(rs))
        .optional();
  }

  @Override
  public List<RenderJob> findByRequest(UUID requestId) {
    return jdbc.sql("SELECT * FROM docgen.render_job WHERE request_id = ? ORDER BY id")
        .param(requestId)
        .query((rs, i) -> job(rs))
        .list();
  }

  @Override
  public boolean insertIfAbsent(RenderJob j) {
    return jdbc.sql(
                """
                INSERT INTO docgen.render_job
                  (id, tenant_id, template_id, request_event_id, request_id, form_instance_id,
                   input_sha256, status, attempts, error, created_at, created_by, updated_at,
                   updated_by, row_version, package_id, policy_pack_code, policy_pack_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                ON CONFLICT (request_event_id) DO NOTHING
                """)
            .param(j.id())
            .param(j.tenantId())
            .param(j.templateId())
            .param(j.requestEventId())
            .param(j.requestId())
            .param(j.formInstanceId())
            .param(j.inputSha256())
            .param(j.status().name())
            .param(j.attempts())
            .param(j.error())
            .param(ts(j.createdAt()))
            .param(j.createdBy())
            .param(ts(j.updatedAt()))
            .param(j.createdBy())
            .param(j.provenance().packageId())
            .param(j.provenance().policyPackCode())
            .param(j.provenance().policyPackVersion())
            .update()
        == 1;
  }

  @Override
  public long update(RenderJob j) {
    int rows =
        jdbc.sql(
                """
                UPDATE docgen.render_job
                   SET status = ?, attempts = ?, error = ?, updated_at = ?,
                       row_version = row_version + 1
                 WHERE id = ? AND row_version = ?
                """)
            .param(j.status().name())
            .param(j.attempts())
            .param(j.error())
            .param(ts(j.updatedAt()))
            .param(j.id())
            .param(j.rowVersion())
            .update();
    if (rows != 1) {
      throw new IllegalStateException("Tác vụ sinh " + j.id() + " vừa bị cập nhật đồng thời");
    }
    return j.rowVersion() + 1;
  }

  @Override
  public void insertOutput(RenderOutput o) {
    jdbc.sql(
            """
            INSERT INTO docgen.render_output
              (id, tenant_id, render_job_id, output_kind, object_key, pdf_sha256, size_bytes,
               pdfa_validated)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .param(o.id())
        .param(o.tenantId())
        .param(o.renderJobId())
        .param(o.kind().name())
        .param(o.objectKey())
        .param(o.pdfSha256())
        .param(o.sizeBytes())
        .param(o.pdfaValidated())
        .update();
  }

  @Override
  public List<RenderOutput> outputsOf(UUID renderJobId) {
    return jdbc.sql("SELECT * FROM docgen.render_output WHERE render_job_id = ? ORDER BY id")
        .param(renderJobId)
        .query(
            (rs, i) ->
                new RenderOutput(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("render_job_id", UUID.class),
                    OutputKind.valueOf(rs.getString("output_kind")),
                    rs.getString("object_key"),
                    rs.getBytes("pdf_sha256"),
                    rs.getLong("size_bytes"),
                    rs.getBoolean("pdfa_validated")))
        .list();
  }

  private static RenderJob job(ResultSet rs) throws SQLException {
    return new RenderJob(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("template_id", UUID.class),
            rs.getObject("request_event_id", UUID.class),
            rs.getObject("request_id", UUID.class),
            rs.getObject("form_instance_id", UUID.class),
            rs.getBytes("input_sha256"),
            RenderJobStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            rs.getString("error"),
            rs.getObject("created_by", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getLong("row_version"))
        .withProvenance(
            new RenderJob.Provenance(
                rs.getObject("package_id", UUID.class),
                rs.getString("policy_pack_code"),
                rs.getString("policy_pack_version")));
  }

  private static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
