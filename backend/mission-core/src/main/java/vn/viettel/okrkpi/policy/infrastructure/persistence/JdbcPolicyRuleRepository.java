package vn.viettel.okrkpi.policy.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.policy.application.port.PolicyRuleRepository;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link PolicyRuleRepository} trên {@code policy.policy_rule}, ghi {@code tenant_id} tường minh.
 */
public class JdbcPolicyRuleRepository implements PolicyRuleRepository {

  private static final String COLUMNS =
      "id, policy_pack_version_id, rule_key, expression, result_type, rounding::text AS rounding,"
          + " legal_ref, eval_order, params_json::text AS params_json, overridable, row_version";

  private final JdbcClient jdbc;

  public JdbcPolicyRuleRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<StoredRule> storedRules(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM policy.policy_rule WHERE tenant_id = ? AND policy_pack_version_id = ?"
                + " ORDER BY eval_order, rule_key")
        .param(tenant.value())
        .param(versionId)
        .query(JdbcPolicyRuleRepository::stored)
        .list();
  }

  @Override
  public Optional<StoredRule> storedRule(TenantId tenant, UUID versionId, String ruleKey) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM policy.policy_rule WHERE tenant_id = ? AND policy_pack_version_id = ?"
                + " AND rule_key = ?")
        .param(tenant.value())
        .param(versionId)
        .param(ruleKey)
        .query(JdbcPolicyRuleRepository::stored)
        .optional();
  }

  @Override
  public boolean replaceRule(TenantId tenant, PolicyRule rule, long expected, UUID actor) {
    return jdbc.sql(
                """
                UPDATE policy.policy_rule
                   SET expression = ?, result_type = ?, rounding = ?::jsonb, legal_ref = ?,
                       eval_order = ?, params_json = ?::jsonb, overridable = ?,
                       updated_at = now(), updated_by = ?, row_version = row_version + 1
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(rule.expression())
            .param(rule.resultType())
            .param(rule.roundingJson())
            .param(rule.legalRef())
            .param(rule.evalOrder())
            .param(rule.paramsJson())
            .param(rule.overridable())
            .param(actor)
            .param(tenant.value())
            .param(rule.id())
            .param(expected)
            .update()
        == 1;
  }

  @Override
  public void appendRule(TenantId tenant, PolicyRule rule, UUID actor) {
    jdbc.sql(
            """
            INSERT INTO policy.policy_rule
              (id, tenant_id, created_by, updated_by, policy_pack_version_id, rule_key,
               expression, result_type, rounding, legal_ref, eval_order, params_json, overridable)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?)
            """)
        .param(rule.id())
        .param(tenant.value())
        .param(actor)
        .param(actor)
        .param(rule.versionId())
        .param(rule.ruleKey())
        .param(rule.expression())
        .param(rule.resultType())
        .param(rule.roundingJson())
        .param(rule.legalRef())
        .param(rule.evalOrder())
        .param(rule.paramsJson())
        .param(rule.overridable())
        .update();
  }

  private static StoredRule stored(ResultSet rs, int i) throws SQLException {
    return new StoredRule(
        new PolicyRule(
            rs.getObject("id", UUID.class),
            rs.getObject("policy_pack_version_id", UUID.class),
            rs.getString("rule_key"),
            rs.getString("expression"),
            rs.getString("result_type"),
            rs.getString("rounding"),
            rs.getString("legal_ref"),
            rs.getInt("eval_order"),
            rs.getString("params_json"),
            rs.getBoolean("overridable")),
        rs.getLong("row_version"));
  }
}
