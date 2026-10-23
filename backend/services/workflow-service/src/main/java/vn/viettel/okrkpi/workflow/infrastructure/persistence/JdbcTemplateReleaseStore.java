package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.port.TemplateReleaseStore;
import vn.viettel.okrkpi.workflow.domain.template.Release;
import vn.viettel.okrkpi.workflow.domain.template.ReleaseStatus;

/** {@link TemplateReleaseStore} trên {@code flowable.wf_template_release} (bảng bổ sung V7). */
public class JdbcTemplateReleaseStore implements TemplateReleaseStore {

  private static final String COLUMNS =
      "wf_template_id, merge_request_ref, merge_request_url, status, submitted_by, approved_by,"
          + " saga_instance_id, signature, pipeline_ref";

  private final JdbcTemplate jdbc;

  public JdbcTemplateReleaseStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Release r, UUID actor) {
    UUID tenant = bind();
    jdbc.update(
        "INSERT INTO flowable.wf_template_release (id, tenant_id, wf_template_id, merge_request_ref,"
            + " merge_request_url, status, submitted_by, created_by, updated_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (wf_template_id) DO NOTHING",
        Ids.newUuidV7(),
        tenant,
        r.templateId(),
        r.mergeRequestRef(),
        r.mergeRequestUrl(),
        r.status().name(),
        r.submittedBy(),
        actor,
        actor);
  }

  @Override
  public Optional<Release> find(UUID templateId) {
    bind();
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM flowable.wf_template_release WHERE wf_template_id = ?",
            JdbcTemplateReleaseStore::map,
            templateId)
        .stream()
        .findFirst();
  }

  @Override
  public void approved(UUID templateId, UUID approver, UUID sagaId) {
    bind();
    jdbc.update(
        "UPDATE flowable.wf_template_release SET approved_by = ?, saga_instance_id = ?,"
            + " status = CASE WHEN status = 'MO' THEN 'DA_DUYET' ELSE status END,"
            + " updated_by = ?, updated_at = now(), row_version = row_version + 1"
            + " WHERE wf_template_id = ?",
        approver,
        sagaId,
        approver,
        templateId);
  }

  @Override
  public boolean signed(UUID templateId, byte[] signature, String pipelineRef) {
    bind();
    return jdbc.update(
            "UPDATE flowable.wf_template_release SET signature = ?, pipeline_ref = ?,"
                + " signed_at = now(), status = 'DA_KY', updated_at = now(),"
                + " row_version = row_version + 1"
                + " WHERE wf_template_id = ? AND signature IS NULL",
            signature,
            pipelineRef,
            templateId)
        == 1;
  }

  @Override
  public void applied(UUID templateId) {
    bind();
    jdbc.update(
        "UPDATE flowable.wf_template_release SET status = 'DA_AP_DUNG', updated_at = now(),"
            + " row_version = row_version + 1 WHERE wf_template_id = ? AND status = 'DA_KY'",
        templateId);
  }

  @Override
  public List<Release> signedNotApplied(int limit) {
    bind();
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM flowable.wf_template_release WHERE status = 'DA_KY'"
            + " AND saga_instance_id IS NOT NULL ORDER BY signed_at LIMIT ?",
        JdbcTemplateReleaseStore::map,
        limit);
  }

  private UUID bind() {
    UUID tenant = TenantContext.require().value();
    TenantBinding.bind(jdbc, tenant);
    return tenant;
  }

  private static Release map(ResultSet rs, int row) throws SQLException {
    return new Release(
        rs.getObject("wf_template_id", UUID.class),
        rs.getString("merge_request_ref"),
        rs.getString("merge_request_url"),
        ReleaseStatus.valueOf(rs.getString("status")),
        rs.getObject("submitted_by", UUID.class),
        rs.getObject("approved_by", UUID.class),
        rs.getObject("saga_instance_id", UUID.class),
        rs.getBytes("signature"),
        rs.getString("pipeline_ref"));
  }
}
