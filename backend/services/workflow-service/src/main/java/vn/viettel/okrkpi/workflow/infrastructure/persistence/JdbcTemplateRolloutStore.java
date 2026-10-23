package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.port.TemplateRolloutStore;
import vn.viettel.okrkpi.workflow.domain.template.CellRollout;
import vn.viettel.okrkpi.workflow.domain.template.RolloutStatus;

/** {@link TemplateRolloutStore} trên {@code flowable.wf_template_rollout} (bổ sung BS-01, V7). */
public class JdbcTemplateRolloutStore implements TemplateRolloutStore {

  private static final String COLUMNS =
      "wf_template_id, cell_code, seq_no, status, flowable_deployment_id, deployed_at,"
          + " previous_version_no";

  private final JdbcTemplate jdbc;

  public JdbcTemplateRolloutStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void plan(UUID templateId, List<String> cells, UUID sagaId, UUID actor) {
    UUID tenant = bind();
    for (int i = 0; i < cells.size(); i++) {
      jdbc.update(
          "INSERT INTO flowable.wf_template_rollout (id, tenant_id, wf_template_id, cell_code,"
              + " seq_no, status, saga_instance_id, created_by, updated_by)"
              + " VALUES (?, ?, ?, ?, ?, 'CHO', ?, ?, ?)"
              + " ON CONFLICT (wf_template_id, cell_code) DO UPDATE SET seq_no = excluded.seq_no,"
              + " saga_instance_id = excluded.saga_instance_id, updated_by = excluded.updated_by,"
              + " updated_at = now(), row_version = flowable.wf_template_rollout.row_version + 1",
          Ids.newUuidV7(),
          tenant,
          templateId,
          cells.get(i),
          i + 1,
          sagaId,
          actor,
          actor);
    }
  }

  @Override
  public List<CellRollout> list(UUID templateId) {
    bind();
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM flowable.wf_template_rollout WHERE wf_template_id = ? ORDER BY seq_no",
        JdbcTemplateRolloutStore::map,
        templateId);
  }

  @Override
  public Optional<CellRollout> find(UUID templateId, String cellCode) {
    bind();
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + " FROM flowable.wf_template_rollout WHERE wf_template_id = ? AND cell_code = ?",
            JdbcTemplateRolloutStore::map,
            templateId,
            cellCode)
        .stream()
        .findFirst();
  }

  @Override
  public void deployed(
      UUID templateId,
      String cellCode,
      String deploymentId,
      Integer previousVersionNo,
      Instant at) {
    bind();
    jdbc.update(
        "UPDATE flowable.wf_template_rollout SET status = 'DA_TRIEN_KHAI',"
            + " flowable_deployment_id = ?, previous_version_no = ?, deployed_at = ?,"
            + " last_error = NULL, updated_at = now(), row_version = row_version + 1"
            + " WHERE wf_template_id = ? AND cell_code = ?",
        deploymentId,
        previousVersionNo,
        Timestamp.from(at),
        templateId,
        cellCode);
  }

  @Override
  public void mark(UUID templateId, String cellCode, RolloutStatus status, String error) {
    bind();
    jdbc.update(
        "UPDATE flowable.wf_template_rollout SET status = ?, last_error = ?, updated_at = now(),"
            + " row_version = row_version + 1 WHERE wf_template_id = ? AND cell_code = ?",
        status.name(),
        error,
        templateId,
        cellCode);
  }

  private UUID bind() {
    UUID tenant = TenantContext.require().value();
    TenantBinding.bind(jdbc, tenant);
    return tenant;
  }

  private static CellRollout map(ResultSet rs, int row) throws SQLException {
    Timestamp deployed = rs.getTimestamp("deployed_at");
    int previous = rs.getInt("previous_version_no");
    Integer previousVersion = rs.wasNull() ? null : previous;
    return new CellRollout(
        rs.getObject("wf_template_id", UUID.class),
        rs.getString("cell_code"),
        rs.getInt("seq_no"),
        RolloutStatus.valueOf(rs.getString("status")),
        rs.getString("flowable_deployment_id"),
        deployed == null ? null : deployed.toInstant(),
        previousVersion);
  }
}
