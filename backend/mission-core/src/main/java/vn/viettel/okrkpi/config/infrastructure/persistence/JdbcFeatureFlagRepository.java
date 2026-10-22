package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.FeatureFlagRepository;
import vn.viettel.okrkpi.config.domain.FeatureFlag;
import vn.viettel.okrkpi.config.domain.FeatureFlagRollout;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kho {@code config.feature_flag} bằng JDBC (CORE-08-07). Cột {@code rollout} lưu {@code
 * {"unitIds": [...], "percent": n}} (suy luận định dạng, wiki trang 14).
 */
public class JdbcFeatureFlagRepository implements FeatureFlagRepository {

  private static final String COLUMNS =
      "id, flag_key, enabled, rollout::text AS rollout, valid_from, valid_to, row_version";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcFeatureFlagRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public List<FeatureFlag> findAll(TenantId tenantId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM config.feature_flag WHERE tenant_id = ? ORDER BY flag_key")
        .param(tenantId.value())
        .query(mapper())
        .list();
  }

  @Override
  public Optional<FeatureFlag> find(TenantId tenantId, String key) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM config.feature_flag WHERE tenant_id = ? AND flag_key = ?")
        .param(tenantId.value())
        .param(key)
        .query(mapper())
        .optional();
  }

  @Override
  public boolean save(TenantId tenantId, FeatureFlag f, int expectedVersion, UUID actorId) {
    Timestamp now = Timestamp.from(Instant.now());
    if (expectedVersion == 0) {
      return jdbc.sql(
                  """
                  INSERT INTO config.feature_flag
                    (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
                     flag_key, enabled, rollout, valid_from, valid_to)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                  ON CONFLICT (tenant_id, flag_key) DO NOTHING
                  """)
              .param(f.id())
              .param(tenantId.value())
              .param(now)
              .param(actorId)
              .param(now)
              .param(actorId)
              .param(f.rowVersion())
              .param(f.key())
              .param(f.enabled())
              .param(rollout(f.rollout()))
              .param(ts(f.validFrom()))
              .param(ts(f.validTo()))
              .update()
          == 1;
    }
    return jdbc.sql(
                """
                UPDATE config.feature_flag
                   SET enabled = ?, rollout = ?::jsonb, valid_from = ?, valid_to = ?,
                       row_version = ?, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND flag_key = ? AND row_version = ?
                """)
            .param(f.enabled())
            .param(rollout(f.rollout()))
            .param(ts(f.validFrom()))
            .param(ts(f.validTo()))
            .param(f.rowVersion())
            .param(now)
            .param(actorId)
            .param(tenantId.value())
            .param(f.key())
            .param(expectedVersion)
            .update()
        == 1;
  }

  private String rollout(FeatureFlagRollout r) {
    ObjectNode n = json.createObjectNode();
    r.unitIds().stream().sorted().forEach(id -> n.withArray("unitIds").add(id.toString()));
    if (!n.has("unitIds")) {
      n.putArray("unitIds");
    }
    n.put("percent", r.percent());
    return n.toString();
  }

  private RowMapper<FeatureFlag> mapper() {
    return (ResultSet rs, int row) ->
        new FeatureFlag(
            rs.getObject("id", UUID.class),
            rs.getString("flag_key"),
            null,
            rs.getBoolean("enabled"),
            parse(rs.getString("rollout")),
            instant(rs, "valid_from"),
            instant(rs, "valid_to"),
            rs.getInt("row_version"));
  }

  private FeatureFlagRollout parse(String raw) {
    try {
      JsonNode n = json.readTree(raw == null ? "{}" : raw);
      Set<UUID> units = new HashSet<>();
      n.path("unitIds").forEach(u -> units.add(UUID.fromString(u.asText())));
      return new FeatureFlagRollout(units, n.path("percent").asInt(100));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cột rollout không phải JSON", e);
    }
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
