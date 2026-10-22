package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.TenantSettingRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.tenant_setting} bằng JDBC (CORE-08-07). */
public class JdbcTenantSettingRepository implements TenantSettingRepository {

  private static final String COLUMNS =
      "id, setting_key, scope_unit_id, value::text AS value, value_schema, description,"
          + " row_version, updated_at";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcTenantSettingRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public Optional<Setting> find(TenantId tenantId, String key, UUID unitId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.tenant_setting WHERE tenant_id = ? AND setting_key = ?"
                + " AND scope_unit_id IS NOT DISTINCT FROM ?")
        .param(tenantId.value())
        .param(key)
        .param(unitId)
        .query(mapper())
        .optional();
  }

  @Override
  public boolean insert(TenantId tenantId, Setting s, UUID actorId) {
    Timestamp at = Timestamp.from(s.updatedAt());
    return jdbc.sql(
                """
                INSERT INTO config.tenant_setting
                  (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
                   setting_key, scope_unit_id, value, value_schema, description)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT DO NOTHING
                """)
            .param(s.id())
            .param(tenantId.value())
            .param(at)
            .param(actorId)
            .param(at)
            .param(actorId)
            .param(s.rowVersion())
            .param(s.key())
            .param(s.scopeUnitId())
            .param(s.value().toString())
            .param(s.valueSchema())
            .param(s.description())
            .update()
        == 1;
  }

  @Override
  public boolean update(TenantId tenantId, Setting s, int expectedVersion, UUID actorId) {
    return jdbc.sql(
                """
                UPDATE config.tenant_setting
                   SET value = ?::jsonb, value_schema = ?, description = ?,
                       row_version = row_version + 1, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND id = ? AND row_version = ?
                """)
            .param(s.value().toString())
            .param(s.valueSchema())
            .param(s.description())
            .param(Timestamp.from(s.updatedAt()))
            .param(actorId)
            .param(tenantId.value())
            .param(s.id())
            .param(expectedVersion)
            .update()
        == 1;
  }

  private RowMapper<Setting> mapper() {
    return (ResultSet rs, int row) -> {
      try {
        return new Setting(
            rs.getObject("id", UUID.class),
            rs.getString("setting_key"),
            rs.getObject("scope_unit_id", UUID.class),
            json.readTree(rs.getString("value")),
            rs.getString("value_schema"),
            rs.getString("description"),
            rs.getInt("row_version"),
            rs.getTimestamp("updated_at").toInstant());
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("Cột value không phải JSON", e);
      }
    };
  }
}
