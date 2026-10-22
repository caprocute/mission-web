package vn.viettel.okrkpi.okr.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  private static final String TARGET_COLUMNS =
      "id, period_id, period_bucket, owner_unit_id, parent_objective_id, code, title, tier,"
          + " status, version_no, created_by, wf_process_instance_id, row_version";

  /** Cùng một mục tiêu qua các phiên bản: cùng kỳ, đơn vị sở hữu và mã. */
  private static final String SAME_OBJECTIVE =
      " v.period_bucket = :b AND v.tenant_id = :t AND v.period_id = :p"
          + " AND v.owner_unit_id = :unit AND v.code = :code AND v.id <> :id";

  private static ReviewTarget target(ResultSet rs, int row) throws SQLException {
    return new ReviewTarget(
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
        rs.getLong("row_version"));
  }

  private static MapSqlParameterSource sameObjective(TenantId tenant, ReviewTarget target) {
    return t(tenant)
        .addValue("b", target.periodBucket())
        .addValue("p", target.periodId())
        .addValue("unit", target.ownerUnitId())
        .addValue("code", target.code())
        .addValue("id", target.id())
        .addValue("v", target.versionNo());
  }

  @Override
  public Optional<ReviewTarget> lockTarget(TenantId tenant, UUID id) {
    return jdbc
        .query(
            "SELECT "
                + TARGET_COLUMNS
                + " FROM okr.objective WHERE tenant_id = :t AND id = :id"
                + " FOR UPDATE",
            t(tenant).addValue("id", id),
            JdbcOkrReviewRepository::target)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<UUID> pendingRevision(TenantId tenant, ReviewTarget target) {
    return jdbc
        .queryForList(
            "SELECT v.id FROM okr.objective v WHERE"
                + SAME_OBJECTIVE
                + " AND v.version_no > :v AND v.status IN ('NHAP', 'CHO_DUYET') LIMIT 1",
            sameObjective(tenant, target),
            UUID.class)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<ReviewTarget> previousApproved(TenantId tenant, ReviewTarget target) {
    return jdbc
        .query(
            "SELECT "
                + TARGET_COLUMNS
                + " FROM okr.objective v WHERE"
                + SAME_OBJECTIVE
                + " AND v.version_no < :v AND v.status = 'DA_DUYET'"
                + " ORDER BY v.version_no DESC LIMIT 1 FOR UPDATE",
            sameObjective(tenant, target),
            JdbcOkrReviewRepository::target)
        .stream()
        .findFirst();
  }

  @Override
  public List<UUID> childAuthors(TenantId tenant, UUID parentId) {
    return jdbc.queryForList(
        "SELECT DISTINCT created_by FROM okr.objective WHERE tenant_id = :t"
            + " AND parent_objective_id = :id AND status = 'DA_DUYET'",
        t(tenant).addValue("id", parentId),
        UUID.class);
  }

  @Override
  public UUID cloneAsRevision(TenantId tenant, ReviewTarget target, UUID actor) {
    UUID revision = UUID.randomUUID();
    MapSqlParameterSource p =
        sameObjective(tenant, target).addValue("actor", actor).addValue("nid", revision);
    jdbc.update(
        "INSERT INTO okr.objective (id, created_by, updated_by, period_id, period_bucket,"
            + " owner_type, owner_unit_id, owner_person_id, code, title, description,"
            + " parent_objective_id, tier, status, ai_suggestion_id, over_limit_reason, version_no,"
            + " ai_assisted)"
            + " SELECT :nid, :actor, :actor, o.period_id, o.period_bucket, o.owner_type,"
            + " o.owner_unit_id, o.owner_person_id, o.code, o.title, o.description,"
            + " o.parent_objective_id, o.tier, 'NHAP', o.ai_suggestion_id, o.over_limit_reason,"
            + " (SELECT max(v.version_no) FROM okr.objective v WHERE v.period_bucket = :b"
            + " AND v.tenant_id = :t AND v.period_id = :p AND v.owner_unit_id = :unit"
            + " AND v.code = :code) + 1, o.ai_assisted"
            + " FROM okr.objective o WHERE o.period_bucket = :b AND o.tenant_id = :t AND o.id = :id",
        p);
    jdbc.update(
        "INSERT INTO okr.objective_basis (period_bucket, objective_id, basis_id, created_by)"
            + " SELECT period_bucket, :nid, basis_id, :actor FROM okr.objective_basis"
            + " WHERE period_bucket = :b AND tenant_id = :t AND objective_id = :id",
        p);
    Map<UUID, UUID> ids = new HashMap<>();
    ids.put(target.id(), revision);
    for (UUID kr :
        jdbc.queryForList(
            "SELECT id FROM okr.key_result WHERE period_bucket = :b AND tenant_id = :t"
                + " AND objective_id = :id ORDER BY code",
            p,
            UUID.class)) {
      UUID copy = UUID.randomUUID();
      ids.put(kr, copy);
      jdbc.update(
          "INSERT INTO okr.key_result (id, created_by, updated_by, period_id, period_bucket,"
              + " objective_id, code, title, metric_type, unit, baseline_value, target_value,"
              + " direction, weight, due_date, verifier_person_id, data_source, status,"
              + " ai_suggestion_id, formula_text, content_source, ai_assisted, checkin_frequency)"
              + " SELECT :kid, :actor, :actor, period_id, period_bucket, :nid, code, title,"
              + " metric_type, unit, baseline_value, target_value, direction, weight, due_date,"
              + " verifier_person_id, data_source, 'NHAP', ai_suggestion_id, formula_text,"
              + " content_source, ai_assisted, checkin_frequency FROM okr.key_result"
              + " WHERE period_bucket = :b AND tenant_id = :t AND id = :old",
          new MapSqlParameterSource(p.getValues()).addValue("kid", copy).addValue("old", kr));
    }
    copyLinks(tenant, ids, actor);
    return revision;
  }

  /** Sao chép liên kết chạm mục tiêu hoặc KR cũ, thay đầu mút bằng bản sao tương ứng. */
  private void copyLinks(TenantId tenant, Map<UUID, UUID> ids, UUID actor) {
    List<Map<String, Object>> links =
        jdbc.queryForList(
            "SELECT period_id, from_type, from_id, to_type, to_id, link_kind,"
                + " contribution_weight FROM okr.okr_link WHERE tenant_id = :t"
                + " AND ((from_type IN ('OBJECTIVE', 'KEY_RESULT') AND from_id IN (:ids))"
                + " OR (to_type IN ('OBJECTIVE', 'KEY_RESULT') AND to_id IN (:ids)))",
            t(tenant).addValue("ids", ids.keySet()));
    for (Map<String, Object> l : links) {
      UUID from = (UUID) l.get("from_id");
      UUID to = (UUID) l.get("to_id");
      jdbc.update(
          "INSERT INTO okr.okr_link (id, created_by, updated_by, period_id, from_type, from_id,"
              + " to_type, to_id, link_kind, contribution_weight) VALUES (:lid, :actor, :actor,"
              + " :p, :ft, :f, :tt, :to, :kind, :w) ON CONFLICT DO NOTHING",
          t(tenant)
              .addValue("lid", UUID.randomUUID())
              .addValue("actor", actor)
              .addValue("p", l.get("period_id"))
              .addValue("ft", l.get("from_type"))
              .addValue("f", ids.getOrDefault(from, from))
              .addValue("tt", l.get("to_type"))
              .addValue("to", ids.getOrDefault(to, to))
              .addValue("kind", l.get("link_kind"))
              .addValue("w", l.get("contribution_weight")));
    }
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
