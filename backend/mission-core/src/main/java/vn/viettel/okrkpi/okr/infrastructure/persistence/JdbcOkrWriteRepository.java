package vn.viettel.okrkpi.okr.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.LinkRow;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link OkrWriteRepository} trên {@code okrDataSource}. Câu lệnh luôn kèm {@code tenant_id} và,
 * với bảng theo kỳ, {@code period_bucket} để chỉ chạm một phân vùng; RLS là lớp chặn thứ hai.
 */
public class JdbcOkrWriteRepository implements OkrWriteRepository {

  private static final String HEAD_COLUMNS =
      "id, period_id, period_bucket, owner_type, owner_unit_id, owner_person_id, code,"
          + " parent_objective_id, status, row_version";

  private static final String KR_COLUMNS =
      "id, title, metric_type, unit, baseline_value, target_value, direction, weight, due_date,"
          + " verifier_person_id, data_source, formula_text, checkin_frequency, content_source,"
          + " ai_suggestion_id";

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcOkrWriteRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static MapSqlParameterSource t(TenantId tenant) {
    return new MapSqlParameterSource("t", tenant.value());
  }

  @Override
  public ObjectiveText objectiveText(TenantId tenant, int bucket, UUID objectiveId) {
    return jdbc.queryForObject(
        "SELECT title, description, tier FROM okr.objective WHERE period_bucket = :b"
            + " AND tenant_id = :t AND id = :id",
        t(tenant).addValue("b", bucket).addValue("id", objectiveId),
        (rs, row) ->
            new ObjectiveText(
                rs.getString("title"), rs.getString("description"), rs.getInt("tier")));
  }

  @Override
  public void lockObjectiveCodes(TenantId tenant, UUID unitId, UUID periodId) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))",
        new MapSqlParameterSource("k", "okr.objective.code:" + tenant.value() + unitId + periodId),
        rs -> {});
  }

  @Override
  public int maxObjectiveNo(TenantId tenant, int bucket, UUID unitId, UUID periodId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT coalesce(max(substring(code FROM '^O([0-9]{1,9})$')::int), 0)"
                + " FROM okr.objective WHERE period_bucket = :b AND tenant_id = :t"
                + " AND owner_unit_id = :u AND period_id = :p",
            t(tenant).addValue("b", bucket).addValue("u", unitId).addValue("p", periodId),
            Integer.class);
    return n == null ? 0 : n;
  }

  @Override
  public int maxKeyResultNo(TenantId tenant, int bucket, UUID objectiveId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT coalesce(max(substring(code FROM '^KR [^.]+\\.([0-9]{1,9})$')::int), 0)"
                + " FROM okr.key_result WHERE period_bucket = :b AND tenant_id = :t"
                + " AND objective_id = :o",
            t(tenant).addValue("b", bucket).addValue("o", objectiveId),
            Integer.class);
    return n == null ? 0 : n;
  }

  @Override
  public long countOwnerObjectives(
      TenantId tenant, UUID periodId, UUID unitId, UUID personId, UUID exceptId) {
    MapSqlParameterSource p = t(tenant).addValue("p", periodId).addValue("x", exceptId);
    String owner;
    if (personId != null) {
      owner = "owner_type = 'CA_NHAN' AND owner_person_id = :who";
      p.addValue("who", personId);
    } else {
      owner = "owner_type = 'TAP_THE' AND owner_unit_id = :who";
      p.addValue("who", unitId);
    }
    Long n =
        jdbc.queryForObject(
            "SELECT count(*) FROM okr.objective WHERE tenant_id = :t AND period_id = :p"
                + " AND status <> 'DONG' AND "
                + owner
                + " AND (CAST(:x AS uuid) IS NULL OR id <> :x)",
            p,
            Long.class);
    return n == null ? 0 : n;
  }

  @Override
  public void insertObjective(
      TenantId tenant,
      UUID id,
      UUID periodId,
      int bucket,
      String ownerType,
      UUID ownerUnitId,
      UUID ownerPersonId,
      String code,
      ObjectiveData d,
      UUID actor) {
    jdbc.update(
        "INSERT INTO okr.objective (id, tenant_id, period_id, period_bucket, owner_type,"
            + " owner_unit_id, owner_person_id, code, title, description, parent_objective_id,"
            + " tier, status, over_limit_reason, ai_suggestion_id, ai_assisted, version_no,"
            + " created_by, updated_by) VALUES (:id, :t, :p, :b, :ot, :ou, :op, :code, :title,"
            + " :descr, :parent, :tier, 'NHAP', :reason, :ai, :aiAssisted, 1, :actor, :actor)",
        objectiveParams(tenant, d, actor)
            .addValue("id", id)
            .addValue("p", periodId)
            .addValue("b", bucket)
            .addValue("ot", ownerType)
            .addValue("ou", ownerUnitId)
            .addValue("op", ownerPersonId)
            .addValue("code", code));
  }

  @Override
  public long updateObjective(TenantId tenant, ObjectiveHead head, ObjectiveData d, UUID actor) {
    jdbc.update(
        "UPDATE okr.objective SET title = :title, description = :descr,"
            + " parent_objective_id = :parent, tier = :tier, over_limit_reason = :reason,"
            + " ai_suggestion_id = :ai, ai_assisted = :aiAssisted, updated_at = now(),"
            + " updated_by = :actor, row_version = row_version + 1"
            + " WHERE period_bucket = :b AND tenant_id = :t AND id = :id",
        objectiveParams(tenant, d, actor)
            .addValue("b", head.periodBucket())
            .addValue("id", head.id()));
    return head.rowVersion() + 1;
  }

  private static MapSqlParameterSource objectiveParams(
      TenantId tenant, ObjectiveData d, UUID actor) {
    return t(tenant)
        .addValue("title", d.title())
        .addValue("descr", d.description())
        .addValue("parent", d.parentObjectiveId())
        .addValue("tier", d.tier())
        .addValue("reason", d.overLimitReason())
        .addValue("ai", d.aiSuggestionId())
        .addValue("aiAssisted", d.aiSuggestionId() != null)
        .addValue("actor", actor);
  }

  @Override
  public long touchObjective(TenantId tenant, ObjectiveHead head, UUID actor) {
    jdbc.update(
        "UPDATE okr.objective SET updated_at = now(), updated_by = :actor,"
            + " row_version = row_version + 1"
            + " WHERE period_bucket = :b AND tenant_id = :t AND id = :id",
        t(tenant)
            .addValue("actor", actor)
            .addValue("b", head.periodBucket())
            .addValue("id", head.id()));
    return head.rowVersion() + 1;
  }

  @Override
  public Optional<ObjectiveHead> lockObjective(TenantId tenant, UUID id) {
    return head(id, tenant, " FOR UPDATE");
  }

  @Override
  public Optional<ObjectiveHead> findObjective(TenantId tenant, UUID id) {
    return head(id, tenant, "");
  }

  private Optional<ObjectiveHead> head(UUID id, TenantId tenant, String lock) {
    return jdbc
        .query(
            "SELECT "
                + HEAD_COLUMNS
                + " FROM okr.objective WHERE tenant_id = :t AND id = :id"
                + lock,
            t(tenant).addValue("id", id),
            JdbcOkrWriteRepository::head)
        .stream()
        .findFirst();
  }

  private static ObjectiveHead head(ResultSet rs, int row) throws SQLException {
    return new ObjectiveHead(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getString("owner_type"),
        rs.getObject("owner_unit_id", UUID.class),
        rs.getObject("owner_person_id", UUID.class),
        rs.getString("code"),
        rs.getObject("parent_objective_id", UUID.class),
        rs.getString("status"),
        rs.getLong("row_version"));
  }

  @Override
  public Optional<UUID> objectiveOfKeyResult(TenantId tenant, UUID keyResultId) {
    return jdbc
        .queryForList(
            "SELECT objective_id FROM okr.key_result WHERE tenant_id = :t AND id = :id",
            t(tenant).addValue("id", keyResultId),
            UUID.class)
        .stream()
        .findFirst();
  }

  @Override
  public List<UUID> objectivesOf(
      TenantId tenant, UUID periodId, UUID unitId, Collection<UUID> onlyIds) {
    MapSqlParameterSource p = t(tenant).addValue("p", periodId).addValue("u", unitId);
    String only = "";
    if (onlyIds != null && !onlyIds.isEmpty()) {
      only = " AND id IN (:ids)";
      p.addValue("ids", onlyIds);
    }
    return jdbc.queryForList(
        "SELECT id FROM okr.objective WHERE tenant_id = :t AND period_id = :p"
            + " AND owner_unit_id = :u"
            + only
            + " ORDER BY code, id",
        p,
        UUID.class);
  }

  @Override
  public List<UUID> keyResultIds(TenantId tenant, int bucket, UUID objectiveId) {
    return jdbc.queryForList(
        "SELECT id FROM okr.key_result WHERE period_bucket = :b AND tenant_id = :t"
            + " AND objective_id = :o",
        t(tenant).addValue("b", bucket).addValue("o", objectiveId),
        UUID.class);
  }

  @Override
  public void insertKeyResult(
      TenantId tenant,
      UUID id,
      UUID periodId,
      int bucket,
      UUID objectiveId,
      String code,
      KeyResultData d,
      UUID actor) {
    jdbc.update(
        "INSERT INTO okr.key_result (id, tenant_id, period_id, period_bucket, objective_id, code,"
            + " title, metric_type, unit, baseline_value, target_value, direction, weight,"
            + " due_date, verifier_person_id, data_source, formula_text, checkin_frequency,"
            + " content_source, ai_suggestion_id, ai_assisted, status, created_by, updated_by)"
            + " VALUES (:id, :t, :p, :b, :o, :code, :title, :metric, :unit, :base, :target, :dir,"
            + " :weight, :due, :verifier, :src, :formula, :freq, :content, :ai, :aiAssisted,"
            + " 'NHAP', :actor, :actor)",
        keyResultParams(tenant, d, actor)
            .addValue("id", id)
            .addValue("p", periodId)
            .addValue("b", bucket)
            .addValue("o", objectiveId)
            .addValue("code", code));
  }

  @Override
  public void updateKeyResult(TenantId tenant, int bucket, KeyResultData d, UUID actor) {
    jdbc.update(
        "UPDATE okr.key_result SET title = :title, metric_type = :metric, unit = :unit,"
            + " baseline_value = :base, target_value = :target, direction = :dir,"
            + " weight = :weight, due_date = :due, verifier_person_id = :verifier,"
            + " data_source = :src, formula_text = :formula, checkin_frequency = :freq,"
            + " content_source = :content, ai_suggestion_id = :ai, ai_assisted = :aiAssisted,"
            + " updated_at = now(), updated_by = :actor, row_version = row_version + 1"
            + " WHERE period_bucket = :b AND tenant_id = :t AND id = :id",
        keyResultParams(tenant, d, actor).addValue("b", bucket).addValue("id", d.id()));
  }

  private static MapSqlParameterSource keyResultParams(
      TenantId tenant, KeyResultData d, UUID actor) {
    return t(tenant)
        .addValue("title", d.title())
        .addValue("metric", d.metricType())
        .addValue("unit", d.unit())
        .addValue("base", d.baselineValue())
        .addValue("target", d.targetValue())
        .addValue("dir", d.direction())
        .addValue("weight", d.weight())
        .addValue("due", d.dueDate())
        .addValue("verifier", d.verifierPersonId())
        .addValue("src", d.dataSource())
        .addValue("formula", d.formulaText())
        .addValue("freq", d.checkinFrequency())
        .addValue("content", d.contentSource())
        .addValue("ai", d.aiSuggestionId())
        .addValue("aiAssisted", !"HUMAN".equals(d.contentSource()))
        .addValue("actor", actor);
  }

  @Override
  public void deleteKeyResults(TenantId tenant, int bucket, Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return;
    }
    jdbc.update(
        "DELETE FROM okr.key_result WHERE period_bucket = :b AND tenant_id = :t AND id IN (:ids)",
        t(tenant).addValue("b", bucket).addValue("ids", ids));
  }

  @Override
  public List<KeyResultData> keyResultsOf(TenantId tenant, int bucket, UUID objectiveId) {
    return jdbc.query(
        "SELECT "
            + KR_COLUMNS
            + " FROM okr.key_result WHERE period_bucket = :b AND tenant_id = :t"
            + " AND objective_id = :o ORDER BY code, id",
        t(tenant).addValue("b", bucket).addValue("o", objectiveId),
        (rs, row) ->
            new KeyResultData(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("metric_type"),
                rs.getString("unit"),
                rs.getBigDecimal("baseline_value"),
                rs.getBigDecimal("target_value"),
                rs.getString("direction"),
                rs.getBigDecimal("weight"),
                rs.getObject("due_date", java.time.LocalDate.class),
                rs.getObject("verifier_person_id", UUID.class),
                rs.getString("data_source"),
                rs.getString("formula_text"),
                rs.getString("checkin_frequency"),
                rs.getString("content_source"),
                rs.getObject("ai_suggestion_id", UUID.class)));
  }

  @Override
  public List<UUID> basisIdsOf(TenantId tenant, int bucket, UUID objectiveId) {
    return jdbc.queryForList(
        "SELECT basis_id FROM okr.objective_basis WHERE period_bucket = :b AND tenant_id = :t"
            + " AND objective_id = :o ORDER BY basis_id",
        t(tenant).addValue("b", bucket).addValue("o", objectiveId),
        UUID.class);
  }

  @Override
  public void replaceBases(
      TenantId tenant,
      int bucket,
      UUID periodId,
      UUID objectiveId,
      List<UUID> basisIds,
      UUID actor) {
    MapSqlParameterSource p = t(tenant).addValue("b", bucket).addValue("o", objectiveId);
    jdbc.update(
        "DELETE FROM okr.objective_basis WHERE period_bucket = :b AND tenant_id = :t"
            + " AND objective_id = :o",
        p);
    jdbc.update(
        "DELETE FROM okr.okr_link WHERE tenant_id = :t AND from_type = 'BASIS'"
            + " AND to_type = 'OBJECTIVE' AND to_id = :o AND link_kind = 'CAN_CU'",
        p);
    for (UUID basis : basisIds) {
      jdbc.update(
          "INSERT INTO okr.objective_basis (period_bucket, tenant_id, objective_id, basis_id,"
              + " created_by) VALUES (:b, :t, :o, :basis, :actor)",
          t(tenant)
              .addValue("b", bucket)
              .addValue("o", objectiveId)
              .addValue("basis", basis)
              .addValue("actor", actor));
      insertLink(
          tenant,
          UUID.randomUUID(),
          periodId,
          "BASIS",
          basis,
          "OBJECTIVE",
          objectiveId,
          "CAN_CU",
          null,
          actor);
    }
  }

  @Override
  public void replaceParentLink(
      TenantId tenant, UUID periodId, UUID objectiveId, UUID parentObjectiveId, UUID actor) {
    jdbc.update(
        "DELETE FROM okr.okr_link WHERE tenant_id = :t AND from_type = 'OBJECTIVE'"
            + " AND to_type = 'OBJECTIVE' AND to_id = :o AND link_kind = 'PHAN_RA'",
        t(tenant).addValue("o", objectiveId));
    if (parentObjectiveId != null) {
      insertLink(
          tenant,
          UUID.randomUUID(),
          periodId,
          "OBJECTIVE",
          parentObjectiveId,
          "OBJECTIVE",
          objectiveId,
          "PHAN_RA",
          null,
          actor);
    }
  }

  @Override
  public List<UUID> existingBases(TenantId tenant, Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.queryForList(
        "SELECT id FROM okr.basis WHERE tenant_id = :t AND id IN (:ids)",
        t(tenant).addValue("ids", ids),
        UUID.class);
  }

  @Override
  public boolean linkExists(
      TenantId tenant, String fromType, UUID fromId, String toType, UUID toId) {
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM okr.okr_link WHERE tenant_id = :t AND from_type = :ft"
                + " AND from_id = :fi AND to_type = :tt AND to_id = :ti)",
            t(tenant)
                .addValue("ft", fromType)
                .addValue("fi", fromId)
                .addValue("tt", toType)
                .addValue("ti", toId),
            Boolean.class);
    return Boolean.TRUE.equals(exists);
  }

  @Override
  public void insertLink(
      TenantId tenant,
      UUID id,
      UUID periodId,
      String fromType,
      UUID fromId,
      String toType,
      UUID toId,
      String linkKind,
      java.math.BigDecimal contributionWeight,
      UUID actor) {
    jdbc.update(
        "INSERT INTO okr.okr_link (id, tenant_id, period_id, from_type, from_id, to_type, to_id,"
            + " link_kind, contribution_weight, created_by, updated_by) VALUES (:id, :t, :p, :ft,"
            + " :fi, :tt, :ti, :kind, :w, :actor, :actor)",
        t(tenant)
            .addValue("id", id)
            .addValue("p", periodId)
            .addValue("ft", fromType)
            .addValue("fi", fromId)
            .addValue("tt", toType)
            .addValue("ti", toId)
            .addValue("kind", linkKind)
            .addValue("w", contributionWeight)
            .addValue("actor", actor));
  }

  @Override
  public Optional<LinkRow> findLink(TenantId tenant, UUID id) {
    return jdbc
        .query(
            "SELECT id, period_id, from_type, from_id, to_type, to_id, link_kind,"
                + " contribution_weight, row_version FROM okr.okr_link"
                + " WHERE tenant_id = :t AND id = :id",
            t(tenant).addValue("id", id),
            (rs, row) ->
                new LinkRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("period_id", UUID.class),
                    rs.getString("from_type"),
                    rs.getObject("from_id", UUID.class),
                    rs.getString("to_type"),
                    rs.getObject("to_id", UUID.class),
                    rs.getString("link_kind"),
                    rs.getBigDecimal("contribution_weight"),
                    rs.getLong("row_version")))
        .stream()
        .findFirst();
  }

  @Override
  public void deleteLink(TenantId tenant, UUID id) {
    jdbc.update(
        "DELETE FROM okr.okr_link WHERE tenant_id = :t AND id = :id", t(tenant).addValue("id", id));
  }

  @Override
  public void deleteLinksTouching(TenantId tenant, Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return;
    }
    jdbc.update(
        "DELETE FROM okr.okr_link WHERE tenant_id = :t AND (from_id IN (:ids) OR to_id IN (:ids))",
        t(tenant).addValue("ids", ids));
  }
}
