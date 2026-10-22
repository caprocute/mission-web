package vn.viettel.okrkpi.policy.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.policy.application.port.PolicyDecisionTableRepository;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link PolicyDecisionTableRepository} trên {@code policy.decision_table}, ghi {@code tenant_id}
 * tường minh.
 */
public class JdbcPolicyDecisionTableRepository implements PolicyDecisionTableRepository {

  private static final String COLUMNS =
      "id, policy_pack_version_id, table_key, dmn_xml, hit_policy, dmn_hash, legal_ref,"
          + " rules_json::text AS rules_json,"
          + " explanation_templates_json::text AS explanation_templates_json, row_version";

  private final JdbcClient jdbc;

  public JdbcPolicyDecisionTableRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<StoredTable> storedTables(TenantId tenant, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM policy.decision_table WHERE tenant_id = ? AND policy_pack_version_id = ?"
                + " ORDER BY table_key")
        .param(tenant.value())
        .param(versionId)
        .query(JdbcPolicyDecisionTableRepository::stored)
        .list();
  }

  @Override
  public Optional<StoredTable> storedTable(TenantId tenant, UUID versionId, String tableKey) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM policy.decision_table WHERE tenant_id = ? AND policy_pack_version_id = ?"
                + " AND table_key = ?")
        .param(tenant.value())
        .param(versionId)
        .param(tableKey)
        .query(JdbcPolicyDecisionTableRepository::stored)
        .optional();
  }

  @Override
  public boolean replaceTable(TenantId tenant, DecisionTable t, long expected, UUID actor) {
    return jdbc.sql(
                """
                UPDATE policy.decision_table
                   SET dmn_xml = ?, hit_policy = ?, dmn_hash = ?, legal_ref = ?,
                       rules_json = ?::jsonb, explanation_templates_json = ?::jsonb,
                       updated_at = now(), updated_by = ?, row_version = row_version + 1
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(t.dmnXml())
            .param(t.hitPolicy())
            .param(t.dmnHash())
            .param(t.legalRef())
            .param(t.rulesJson())
            .param(t.explanationTemplatesJson())
            .param(actor)
            .param(tenant.value())
            .param(t.id())
            .param(expected)
            .update()
        == 1;
  }

  @Override
  public void appendTable(TenantId tenant, DecisionTable t, UUID actor) {
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

  private static StoredTable stored(ResultSet rs, int i) throws SQLException {
    return new StoredTable(
        new DecisionTable(
            rs.getObject("id", UUID.class),
            rs.getObject("policy_pack_version_id", UUID.class),
            rs.getString("table_key"),
            rs.getString("dmn_xml"),
            rs.getString("hit_policy"),
            rs.getBytes("dmn_hash"),
            rs.getString("legal_ref"),
            rs.getString("rules_json"),
            rs.getString("explanation_templates_json")),
        rs.getLong("row_version"));
  }
}
