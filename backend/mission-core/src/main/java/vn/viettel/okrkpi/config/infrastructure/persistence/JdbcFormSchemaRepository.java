package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.FormSchemaRepository;
import vn.viettel.okrkpi.config.domain.FormSchema;
import vn.viettel.okrkpi.config.domain.FormSchemaStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.form_schema} bằng JDBC (CORE-08-13). */
public class JdbcFormSchemaRepository implements FormSchemaRepository {

  private static final String COLUMNS =
      "id, form_key, version, status, schema_json::text AS schema_json, ui_json::text AS ui_json,"
          + " cel_rules::text AS cel_rules, policy_pack_version_id,"
          + " last_test_run::text AS last_test_run, created_by, created_at, updated_by, updated_at,"
          + " row_version";

  private final JdbcClient jdbc;

  public JdbcFormSchemaRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<FormSchema> listLatest(TenantId tenantId, String query, String afterKey, int limit) {
    String like = query == null || query.isBlank() ? null : "%" + query.strip() + "%";
    return jdbc.sql(
            "SELECT DISTINCT ON (form_key) "
                + COLUMNS
                + " FROM config.form_schema WHERE tenant_id = ?"
                + " AND (?::varchar IS NULL OR form_key > ?)"
                + " AND (?::varchar IS NULL OR form_key ILIKE ?)"
                + " ORDER BY form_key, id DESC LIMIT ?")
        .param(tenantId.value())
        .param(afterKey)
        .param(afterKey)
        .param(like)
        .param(like)
        .param(limit)
        .query(mapper())
        .list();
  }

  @Override
  public Optional<FormSchema> find(TenantId tenantId, String formKey, String version) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.form_schema WHERE tenant_id = ? AND form_key = ? AND version = ?")
        .param(tenantId.value())
        .param(formKey)
        .param(version)
        .query(mapper())
        .optional();
  }

  @Override
  public List<FormSchema> listVersions(TenantId tenantId, String formKey) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.form_schema WHERE tenant_id = ? AND form_key = ? ORDER BY id")
        .param(tenantId.value())
        .param(formKey)
        .query(mapper())
        .list();
  }

  @Override
  public Optional<FormSchema> findCurrent(TenantId tenantId, String formKey) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.form_schema WHERE tenant_id = ? AND form_key = ?"
                + " AND status = 'DA_PHAT_HANH' ORDER BY updated_at DESC, id DESC LIMIT 1")
        .param(tenantId.value())
        .param(formKey)
        .query(mapper())
        .optional();
  }

  @Override
  public boolean insert(TenantId tenantId, FormSchema f) {
    try {
      return jdbc.sql(
                  """
                  INSERT INTO config.form_schema
                    (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
                     form_key, version, schema_json, ui_json, cel_rules, policy_pack_version_id,
                     status, last_test_run)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb)
                  ON CONFLICT (tenant_id, form_key, version) DO NOTHING
                  """)
              .param(f.id())
              .param(tenantId.value())
              .param(Timestamp.from(f.createdAt()))
              .param(f.createdBy())
              .param(Timestamp.from(f.updatedAt()))
              .param(f.updatedBy())
              .param(f.rowVersion())
              .param(f.formKey())
              .param(f.version())
              .param(f.schemaJson())
              .param(f.uiJson())
              .param(f.celRulesJson())
              .param(f.policyPackVersionId())
              .param(f.status().name())
              .param(f.lastTestRunJson())
              .update()
          == 1;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  @Override
  public boolean update(TenantId tenantId, FormSchema f, int expectedVersion) {
    return jdbc.sql(
                """
                UPDATE config.form_schema
                   SET status = ?, schema_json = ?::jsonb, ui_json = ?::jsonb, cel_rules = ?::jsonb,
                       policy_pack_version_id = ?, last_test_run = ?::jsonb, updated_at = ?,
                       updated_by = ?, row_version = ?
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(f.status().name())
            .param(f.schemaJson())
            .param(f.uiJson())
            .param(f.celRulesJson())
            .param(f.policyPackVersionId())
            .param(f.lastTestRunJson())
            .param(Timestamp.from(f.updatedAt()))
            .param(f.updatedBy())
            .param(f.rowVersion())
            .param(tenantId.value())
            .param(f.id())
            .param(expectedVersion)
            .update()
        == 1;
  }

  private static RowMapper<FormSchema> mapper() {
    return (ResultSet rs, int row) ->
        new FormSchema(
            rs.getObject("id", UUID.class),
            rs.getString("form_key"),
            rs.getString("version"),
            FormSchemaStatus.valueOf(rs.getString("status")),
            rs.getString("schema_json"),
            rs.getString("ui_json"),
            rs.getString("cel_rules"),
            rs.getObject("policy_pack_version_id", UUID.class),
            rs.getString("last_test_run"),
            rs.getObject("created_by", UUID.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getObject("updated_by", UUID.class),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getInt("row_version"));
  }
}
