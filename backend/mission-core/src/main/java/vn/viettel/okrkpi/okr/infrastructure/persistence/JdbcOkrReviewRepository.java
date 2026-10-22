package vn.viettel.okrkpi.okr.infrastructure.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import vn.viettel.okrkpi.okr.application.port.OkrReviewRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-01-06: quyết định duyệt mục tiêu trên {@code okrDataSource} (RLS theo tenant). */
public class JdbcOkrReviewRepository implements OkrReviewRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcOkrReviewRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  private static MapSqlParameterSource t(TenantId tenant) {
    return new MapSqlParameterSource("t", tenant.value());
  }

  private static MapSqlParameterSource row(TenantId tenant, ReviewTarget target, UUID actor) {
    return t(tenant)
        .addValue("b", target.periodBucket())
        .addValue("id", target.id())
        .addValue("actor", actor);
  }

  @Override
  public Optional<ReviewTarget> lockTarget(TenantId tenant, UUID id) {
    return jdbc
        .query(
            "SELECT id, period_id, period_bucket, owner_unit_id, parent_objective_id, code, title,"
                + " tier, status, version_no, created_by, wf_process_instance_id, row_version"
                + " FROM okr.objective WHERE tenant_id = :t AND id = :id FOR UPDATE",
            t(tenant).addValue("id", id),
            (rs, i) ->
                new ReviewTarget(
                    rs.getObject("id", UUID.class),
                    rs.getObject("period_id", UUID.class),
                    rs.getInt("period_bucket"),
                    rs.getObject("owner_unit_id", UUID.class),
                    rs.getObject("parent_objective_id", UUID.class),
                    rs.getString("code"),
                    rs.getString("title"),
                    rs.getInt("tier"),
                    rs.getString("status"),
                    rs.getInt("version_no"),
                    rs.getObject("created_by", UUID.class),
                    rs.getString("wf_process_instance_id"),
                    rs.getLong("row_version")))
        .stream()
        .findFirst();
  }

  @Override
  public long markApproved(TenantId tenant, ReviewTarget target, UUID approver) {
    jdbc.update(
        "UPDATE okr.objective SET status = 'DA_DUYET', approved_by = :actor,"
            + " approved_at = now(), updated_at = now(), updated_by = :actor,"
            + " row_version = row_version + 1"
            + " WHERE period_bucket = :b AND tenant_id = :t AND id = :id",
        row(tenant, target, approver));
    keyResults(tenant, target, approver, "DA_DUYET");
    return target.rowVersion() + 1;
  }

  @Override
  public long reopenDraft(TenantId tenant, ReviewTarget target, UUID actor, boolean nextRound) {
    jdbc.update(
        "UPDATE okr.objective SET status = 'NHAP', version_no = version_no + :inc,"
            + " updated_at = now(), updated_by = :actor, row_version = row_version + 1"
            + " WHERE period_bucket = :b AND tenant_id = :t AND id = :id",
        row(tenant, target, actor).addValue("inc", nextRound ? 1 : 0));
    keyResults(tenant, target, actor, "NHAP");
    return target.rowVersion() + 1;
  }

  @Override
  public long markClosed(TenantId tenant, ReviewTarget target, UUID actor) {
    jdbc.update(
        "UPDATE okr.objective SET status = 'DONG', updated_at = now(), updated_by = :actor,"
            + " row_version = row_version + 1"
            + " WHERE period_bucket = :b AND tenant_id = :t AND id = :id",
        row(tenant, target, actor));
    keyResults(tenant, target, actor, "DONG");
    return target.rowVersion() + 1;
  }

  private void keyResults(TenantId tenant, ReviewTarget target, UUID actor, String status) {
    jdbc.update(
        "UPDATE okr.key_result SET status = :status, updated_at = now(), updated_by = :actor,"
            + " row_version = row_version + 1"
            + " WHERE period_bucket = :b AND tenant_id = :t AND objective_id = :id",
        row(tenant, target, actor).addValue("status", status));
  }

  @Override
  public void appendReview(
      TenantId tenant, ReviewTarget target, UUID reviewer, String decision, String comment) {
    jdbc.update(
        "INSERT INTO okr.objective_review (id, created_at, created_by, updated_at, updated_by,"
            + " period_id, period_bucket, objective_id, version_no, reviewer_id, decision,"
            + " comment) VALUES (:rid, clock_timestamp(), :actor, clock_timestamp(), :actor, :p,"
            + " :b, :id, :v, :actor, :decision, :comment)",
        row(tenant, target, reviewer)
            .addValue("rid", UUID.randomUUID())
            .addValue("p", target.periodId())
            .addValue("v", target.versionNo())
            .addValue("decision", decision)
            .addValue("comment", comment));
  }

  @Override
  public List<UUID> basisIds(TenantId tenant, int bucket, UUID objectiveId) {
    return jdbc.queryForList(
        "SELECT basis_id FROM okr.objective_basis WHERE period_bucket = :b AND tenant_id = :t"
            + " AND objective_id = :id ORDER BY basis_id",
        t(tenant).addValue("b", bucket).addValue("id", objectiveId),
        UUID.class);
  }

  @Override
  public List<ReviewRow> reviews(TenantId tenant, UUID objectiveId) {
    return jdbc.query(
        "SELECT id, version_no, reviewer_id, decision, comment, created_at"
            + " FROM okr.objective_review WHERE tenant_id = :t AND objective_id = :id"
            + " ORDER BY created_at DESC, id DESC",
        t(tenant).addValue("id", objectiveId),
        (rs, i) ->
            new ReviewRow(
                rs.getObject("id", UUID.class),
                rs.getInt("version_no"),
                rs.getObject("reviewer_id", UUID.class),
                rs.getString("decision"),
                rs.getString("comment"),
                rs.getObject("created_at", Timestamp.class).toInstant()));
  }
}
