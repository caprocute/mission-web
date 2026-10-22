package vn.viettel.okrkpi.policy.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyBinding;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@link PolicyPackRepository} trên {@code policyDataSource} (vai trò {@code svc_policy}). */
public class JdbcPolicyPackRepository implements PolicyPackRepository {

  private static final String PACK_COLUMNS =
      "id, code, name, sector, flow, origin, parent_pack_id, legal_basis, created_at, created_by,"
          + " row_version";

  private static final String VERSION_COLUMNS =
      "id, policy_pack_id, version, status, effective_from, manifest::text AS manifest,"
          + " content_hash, golden_pass_rate, last_test_run_id, signature IS NOT NULL AS signed,"
          + " approved_by, approved_at, signed_at, published_at, source_version_id, change_note,"
          + " created_at, created_by, updated_at, updated_by, row_version";

  private static final String RULE_COLUMNS =
      "id, policy_pack_version_id, rule_key, expression, result_type, rounding::text AS rounding,"
          + " legal_ref, eval_order, params_json::text AS params_json, overridable";

  private static final String TABLE_COLUMNS =
      "id, policy_pack_version_id, table_key, dmn_xml, hit_policy, dmn_hash, legal_ref,"
          + " rules_json::text AS rules_json,"
          + " explanation_templates_json::text AS explanation_templates_json";

  private final JdbcClient jdbc;

  public JdbcPolicyPackRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<PolicyPack> listPacks(
      TenantId tenant, String flow, PackOrigin origin, String query, String afterCode, int limit) {
    String like = query == null || query.isBlank() ? null : "%" + query.strip() + "%";
    String originName = origin == null ? null : origin.name();
    return jdbc.sql(
            "SELECT "
                + PACK_COLUMNS
                + " FROM policy.policy_pack WHERE tenant_id = ?"
                + " AND (?::varchar IS NULL OR flow = ?)"
                + " AND (?::varchar IS NULL OR origin = ?)"
                + " AND (?::varchar IS NULL OR code ILIKE ? OR name ILIKE ?)"
                + " AND (?::varchar IS NULL OR code > ?)"
                + " ORDER BY code LIMIT ?")
        .param(tenant.value())
        .param(flow)
        .param(flow)
        .param(originName)
        .param(originName)
        .param(like)
        .param(like)
        .param(like)
        .param(afterCode)
        .param(afterCode)
        .param(limit)
        .query(JdbcPolicyPackRepository::pack)
        .list();
  }

  @Override
  public Optional<PolicyPack> findPack(TenantId tenant, UUID packId) {
    return jdbc.sql(
            "SELECT " + PACK_COLUMNS + " FROM policy.policy_pack WHERE tenant_id = ? AND id = ?")
        .param(tenant.value())
        .param(packId)
        .query(JdbcPolicyPackRepository::pack)
        .optional();
  }

  @Override
  public Optional<PolicyPack> findPackByCode(TenantId tenant, String code) {
    return jdbc.sql(
            "SELECT " + PACK_COLUMNS + " FROM policy.policy_pack WHERE tenant_id = ? AND code = ?")
        .param(tenant.value())
        .param(code)
        .query(JdbcPolicyPackRepository::pack)
        .optional();
  }

  @Override
  public boolean insertPack(TenantId tenant, PolicyPack p) {
    return jdbc.sql(
                """
                INSERT INTO policy.policy_pack
                  (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
                   code, name, sector, flow, origin, parent_pack_id, legal_basis)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, code) DO NOTHING
                """)
            .param(p.id())
            .param(tenant.value())
            .param(Timestamp.from(p.createdAt()))
            .param(p.createdBy())
            .param(Timestamp.from(p.createdAt()))
            .param(p.createdBy())
            .param(p.rowVersion())
            .param(p.code())
            .param(p.name())
            .param(p.sector())
            .param(p.flow())
            .param(p.origin().name())
            .param(p.parentPackId())
            .param(p.legalBasis())
            .update()
        == 1;
  }

  @Override
  public Map<UUID, List<PolicyPackVersion>> recentVersions(
      TenantId tenant, Collection<UUID> packIds, int perPack) {
    Map<UUID, List<PolicyPackVersion>> result = new LinkedHashMap<>();
    if (packIds.isEmpty()) {
      return result;
    }
    jdbc.sql(
            "SELECT * FROM (SELECT "
                + VERSION_COLUMNS
                + ", row_number() OVER (PARTITION BY policy_pack_id ORDER BY id DESC) AS rn"
                + " FROM policy.policy_pack_version WHERE tenant_id = ? AND policy_pack_id = ANY(?::uuid[]))"
                + " v WHERE rn <= ? ORDER BY policy_pack_id, id DESC")
        .param(tenant.value())
        .param(packIds.stream().map(UUID::toString).toArray(String[]::new))
        .param(perPack)
        .query(JdbcPolicyPackRepository::version)
        .list()
        .forEach(v -> result.computeIfAbsent(v.packId(), k -> new ArrayList<>()).add(v));
    return result;
  }

  @Override
  public List<PolicyPackVersion> listVersions(
      TenantId tenant, UUID packId, PolicyVersionStatus status, UUID afterId, int limit) {
    String statusName = status == null ? null : status.name();
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM policy.policy_pack_version WHERE tenant_id = ? AND policy_pack_id = ?"
                + " AND (?::varchar IS NULL OR status = ?)"
                + " AND (?::uuid IS NULL OR id < ?)"
                + " ORDER BY id DESC LIMIT ?")
        .param(tenant.value())
        .param(packId)
        .param(statusName)
        .param(statusName)
        .param(afterId)
        .param(afterId)
        .param(limit)
        .query(JdbcPolicyPackRepository::version)
        .list();
  }

  @Override
  public List<String> versionNumbers(TenantId tenant, UUID packId) {
    return jdbc.sql(
            "SELECT version FROM policy.policy_pack_version WHERE tenant_id = ?"
                + " AND policy_pack_id = ?")
        .param(tenant.value())
        .param(packId)
        .query(String.class)
        .list();
  }

  @Override
  public Optional<PolicyPackVersion> findVersion(TenantId tenant, UUID packId, String version) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM policy.policy_pack_version WHERE tenant_id = ? AND policy_pack_id = ?"
                + " AND version = ?")
        .param(tenant.value())
        .param(packId)
        .param(version)
        .query(JdbcPolicyPackRepository::version)
        .optional();
  }

  @Override
  public Optional<PolicyPackVersion> findVersionById(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM policy.policy_pack_version WHERE tenant_id = ? AND id = ?")
        .param(tenant.value())
        .param(versionId)
        .query(JdbcPolicyPackRepository::version)
        .optional();
  }

  @Override
  public boolean insertVersion(TenantId tenant, PolicyPackVersion v) {
    return jdbc.sql(
                """
                INSERT INTO policy.policy_pack_version
                  (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
                   policy_pack_id, version, status, manifest, source_version_id, change_note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (policy_pack_id, version) DO NOTHING
                """)
            .param(v.id())
            .param(tenant.value())
            .param(Timestamp.from(v.createdAt()))
            .param(v.createdBy())
            .param(Timestamp.from(v.updatedAt()))
            .param(v.updatedBy())
            .param(v.rowVersion())
            .param(v.packId())
            .param(v.version())
            .param(v.status().name())
            .param(v.manifestJson())
            .param(v.sourceVersionId())
            .param(v.changeNote())
            .update()
        == 1;
  }

  @Override
  public boolean storeStatus(TenantId tenant, PolicyPackVersion v, long expected) {
    return jdbc.sql(
                """
                UPDATE policy.policy_pack_version
                   SET status = ?, published_at = ?, updated_at = ?, updated_by = ?, row_version = ?
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(v.status().name())
            .param(v.publishedAt() == null ? null : Timestamp.from(v.publishedAt()))
            .param(Timestamp.from(v.updatedAt()))
            .param(v.updatedBy())
            .param(v.rowVersion())
            .param(tenant.value())
            .param(v.id())
            .param(expected)
            .update()
        == 1;
  }

  @Override
  public List<PolicyRule> rules(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + RULE_COLUMNS
                + " FROM policy.policy_rule WHERE tenant_id = ? AND policy_pack_version_id = ?"
                + " ORDER BY eval_order, rule_key")
        .param(tenant.value())
        .param(versionId)
        .query(JdbcPolicyPackRepository::rule)
        .list();
  }

  @Override
  public Optional<PolicyRule> findRule(TenantId tenant, UUID versionId, String ruleKey) {
    return jdbc.sql(
            "SELECT "
                + RULE_COLUMNS
                + " FROM policy.policy_rule WHERE tenant_id = ? AND policy_pack_version_id = ?"
                + " AND rule_key = ?")
        .param(tenant.value())
        .param(versionId)
        .param(ruleKey)
        .query(JdbcPolicyPackRepository::rule)
        .optional();
  }

  @Override
  public void insertRules(TenantId tenant, List<PolicyRule> rules, UUID actor) {
    for (PolicyRule r : rules) {
      jdbc.sql(
              """
              INSERT INTO policy.policy_rule
                (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key,
                 expression, result_type, rounding, legal_ref, eval_order, params_json, overridable)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?)
              """)
          .param(r.id())
          .param(tenant.value())
          .param(actor)
          .param(actor)
          .param(r.versionId())
          .param(r.ruleKey())
          .param(r.expression())
          .param(r.resultType())
          .param(r.roundingJson())
          .param(r.legalRef())
          .param(r.evalOrder())
          .param(r.paramsJson())
          .param(r.overridable())
          .update();
    }
  }

  @Override
  public List<DecisionTable> decisionTables(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + TABLE_COLUMNS
                + " FROM policy.decision_table WHERE tenant_id = ? AND policy_pack_version_id = ?"
                + " ORDER BY table_key")
        .param(tenant.value())
        .param(versionId)
        .query(JdbcPolicyPackRepository::table)
        .list();
  }

  @Override
  public void insertDecisionTables(TenantId tenant, List<DecisionTable> tables, UUID actor) {
    for (DecisionTable t : tables) {
      jdbc.sql(
              """
              INSERT INTO policy.decision_table
                (id, tenant_id, created_by, updated_by, policy_pack_version_id, table_key, dmn_xml,
                 hit_policy, dmn_hash, legal_ref, rules_json, explanation_templates_json)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
              """)
          .param(t.id())
          .param(tenant.value())
          .param(actor)
          .param(actor)
          .param(t.versionId())
          .param(t.tableKey())
          .param(t.dmnXml())
          .param(t.hitPolicy())
          .param(t.dmnHash())
          .param(t.legalRef())
          .param(t.rulesJson())
          .param(t.explanationTemplatesJson())
          .update();
    }
  }

  @Override
  public List<PolicyBinding> bindings(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT id, period_id, flow, subject_group_id, org_unit_id, is_frozen"
                + " FROM policy.policy_binding WHERE tenant_id = ? AND policy_pack_version_id = ?"
                + " ORDER BY id")
        .param(tenant.value())
        .param(versionId)
        .query(
            (rs, i) ->
                new PolicyBinding(
                    rs.getObject("id", UUID.class),
                    rs.getObject("period_id", UUID.class),
                    rs.getString("flow"),
                    rs.getObject("subject_group_id", UUID.class),
                    rs.getObject("org_unit_id", UUID.class),
                    rs.getBoolean("is_frozen")))
        .list();
  }

  private static PolicyPack pack(ResultSet rs, int i) throws SQLException {
    return new PolicyPack(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("sector"),
        rs.getString("flow"),
        PackOrigin.valueOf(rs.getString("origin")),
        rs.getObject("parent_pack_id", UUID.class),
        rs.getString("legal_basis"),
        instant(rs, "created_at"),
        rs.getObject("created_by", UUID.class),
        rs.getLong("row_version"));
  }

  private static PolicyPackVersion version(ResultSet rs, int i) throws SQLException {
    byte[] hash = rs.getBytes("content_hash");
    return new PolicyPackVersion(
        rs.getObject("id", UUID.class),
        rs.getObject("policy_pack_id", UUID.class),
        rs.getString("version"),
        PolicyVersionStatus.valueOf(rs.getString("status")),
        rs.getObject("effective_from", LocalDate.class),
        rs.getString("manifest"),
        hash == null ? null : HexFormat.of().formatHex(hash),
        rs.getBigDecimal("golden_pass_rate"),
        rs.getObject("last_test_run_id", UUID.class),
        rs.getBoolean("signed"),
        rs.getObject("approved_by", UUID.class),
        instant(rs, "approved_at"),
        instant(rs, "signed_at"),
        instant(rs, "published_at"),
        rs.getObject("source_version_id", UUID.class),
        rs.getString("change_note"),
        instant(rs, "created_at"),
        rs.getObject("created_by", UUID.class),
        instant(rs, "updated_at"),
        rs.getObject("updated_by", UUID.class),
        rs.getLong("row_version"));
  }

  private static PolicyRule rule(ResultSet rs, int i) throws SQLException {
    return new PolicyRule(
        rs.getObject("id", UUID.class),
        rs.getObject("policy_pack_version_id", UUID.class),
        rs.getString("rule_key"),
        rs.getString("expression"),
        rs.getString("result_type"),
        rs.getString("rounding"),
        rs.getString("legal_ref"),
        rs.getInt("eval_order"),
        rs.getString("params_json"),
        rs.getBoolean("overridable"));
  }

  private static DecisionTable table(ResultSet rs, int i) throws SQLException {
    return new DecisionTable(
        rs.getObject("id", UUID.class),
        rs.getObject("policy_pack_version_id", UUID.class),
        rs.getString("table_key"),
        rs.getString("dmn_xml"),
        rs.getString("hit_policy"),
        rs.getBytes("dmn_hash"),
        rs.getString("legal_ref"),
        rs.getString("rules_json"),
        rs.getString("explanation_templates_json"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }
}
