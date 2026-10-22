package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.UserPreferenceRepository;
import vn.viettel.okrkpi.config.domain.SavedFilter;
import vn.viettel.okrkpi.config.domain.UserPreference;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.user_preference} và {@code config.saved_filter} bằng JDBC (CNH-02). */
public class JdbcUserPreferenceRepository implements UserPreferenceRepository {

  private static final String PREF_COLUMNS =
      "id, user_id, pref_key, value::text AS value, value_schema_version, updated_at, row_version";

  private static final String FILTER_COLUMNS =
      "id, user_id, screen_code, filter_name, criteria::text AS criteria, is_default,"
          + " display_order, updated_at, row_version";

  private final JdbcClient jdbc;

  public JdbcUserPreferenceRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<UserPreference> findAll(TenantId tenantId, UUID userId) {
    return jdbc.sql(
            "SELECT "
                + PREF_COLUMNS
                + " FROM config.user_preference WHERE tenant_id = ? AND user_id = ?"
                + " ORDER BY pref_key")
        .param(tenantId.value())
        .param(userId)
        .query(prefMapper())
        .list();
  }

  @Override
  public Optional<UserPreference> find(TenantId tenantId, UUID userId, String prefKey) {
    return jdbc.sql(
            "SELECT "
                + PREF_COLUMNS
                + " FROM config.user_preference WHERE tenant_id = ? AND user_id = ? AND pref_key = ?")
        .param(tenantId.value())
        .param(userId)
        .param(prefKey)
        .query(prefMapper())
        .optional();
  }

  @Override
  public boolean insert(TenantId tenantId, UserPreference p, UUID actorId) {
    Timestamp now = ts(p.updatedAt());
    return jdbc.sql(
                """
                INSERT INTO config.user_preference
                  (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
                   user_id, pref_key, value, value_schema_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (tenant_id, user_id, pref_key) DO NOTHING
                """)
            .param(p.id())
            .param(tenantId.value())
            .param(now)
            .param(actorId)
            .param(now)
            .param(actorId)
            .param(p.rowVersion())
            .param(p.userId())
            .param(p.prefKey())
            .param(p.valueJson())
            .param(p.schemaVersion())
            .update()
        == 1;
  }

  @Override
  public boolean update(TenantId tenantId, UserPreference p, int expectedVersion, UUID actorId) {
    return jdbc.sql(
                """
                UPDATE config.user_preference
                   SET value = ?::jsonb, value_schema_version = ?, row_version = ?,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND user_id = ? AND pref_key = ? AND row_version = ?
                """)
            .param(p.valueJson())
            .param(p.schemaVersion())
            .param(p.rowVersion())
            .param(ts(p.updatedAt()))
            .param(actorId)
            .param(tenantId.value())
            .param(p.userId())
            .param(p.prefKey())
            .param(expectedVersion)
            .update()
        == 1;
  }

  @Override
  public boolean delete(TenantId tenantId, UUID userId, String prefKey, int expectedVersion) {
    return jdbc.sql(
                "DELETE FROM config.user_preference"
                    + " WHERE tenant_id = ? AND user_id = ? AND pref_key = ? AND row_version = ?")
            .param(tenantId.value())
            .param(userId)
            .param(prefKey)
            .param(expectedVersion)
            .update()
        == 1;
  }

  @Override
  public List<SavedFilter> filters(TenantId tenantId, UUID userId, String screenCode) {
    return jdbc.sql(
            "SELECT "
                + FILTER_COLUMNS
                + " FROM config.saved_filter WHERE tenant_id = ? AND user_id = ? AND screen_code = ?"
                + " ORDER BY is_default DESC, display_order, filter_name")
        .param(tenantId.value())
        .param(userId)
        .param(screenCode)
        .query(filterMapper())
        .list();
  }

  @Override
  public Optional<SavedFilter> filter(TenantId tenantId, UUID userId, UUID filterId) {
    return jdbc.sql(
            "SELECT "
                + FILTER_COLUMNS
                + " FROM config.saved_filter WHERE tenant_id = ? AND user_id = ? AND id = ?")
        .param(tenantId.value())
        .param(userId)
        .param(filterId)
        .query(filterMapper())
        .optional();
  }

  @Override
  public void lockScreen(TenantId tenantId, UUID userId, String screenCode) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param("config.saved_filter:" + tenantId.value() + ":" + userId + ":" + screenCode)
        .query()
        .singleValue();
  }

  @Override
  public void clearDefault(
      TenantId tenantId, UUID userId, String screenCode, UUID exceptId, UUID actorId) {
    jdbc.sql(
            """
            UPDATE config.saved_filter
               SET is_default = false, row_version = row_version + 1,
                   updated_at = now(), updated_by = ?
             WHERE tenant_id = ? AND user_id = ? AND screen_code = ? AND is_default AND id <> ?
            """)
        .param(actorId)
        .param(tenantId.value())
        .param(userId)
        .param(screenCode)
        .param(exceptId)
        .update();
  }

  @Override
  public boolean insertFilter(TenantId tenantId, SavedFilter f, UUID actorId) {
    Timestamp now = ts(f.updatedAt());
    return jdbc.sql(
                """
                INSERT INTO config.saved_filter
                  (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
                   user_id, screen_code, filter_name, criteria, is_default, display_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (tenant_id, user_id, screen_code, filter_name) DO NOTHING
                """)
            .param(f.id())
            .param(tenantId.value())
            .param(now)
            .param(actorId)
            .param(now)
            .param(actorId)
            .param(f.rowVersion())
            .param(f.userId())
            .param(f.screenCode())
            .param(f.name())
            .param(f.criteriaJson())
            .param(f.isDefault())
            .param(f.displayOrder())
            .update()
        == 1;
  }

  @Override
  public boolean updateFilter(TenantId tenantId, SavedFilter f, int expectedVersion, UUID actorId) {
    return jdbc.sql(
                """
                UPDATE config.saved_filter
                   SET filter_name = ?, criteria = ?::jsonb, is_default = ?, display_order = ?,
                       row_version = ?, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND user_id = ? AND id = ? AND row_version = ?
                """)
            .param(f.name())
            .param(f.criteriaJson())
            .param(f.isDefault())
            .param(f.displayOrder())
            .param(f.rowVersion())
            .param(ts(f.updatedAt()))
            .param(actorId)
            .param(tenantId.value())
            .param(f.userId())
            .param(f.id())
            .param(expectedVersion)
            .update()
        == 1;
  }

  @Override
  public boolean deleteFilter(TenantId tenantId, UUID userId, UUID filterId, int expectedVersion) {
    return jdbc.sql(
                "DELETE FROM config.saved_filter"
                    + " WHERE tenant_id = ? AND user_id = ? AND id = ? AND row_version = ?")
            .param(tenantId.value())
            .param(userId)
            .param(filterId)
            .param(expectedVersion)
            .update()
        == 1;
  }

  private static RowMapper<UserPreference> prefMapper() {
    return (ResultSet rs, int row) ->
        new UserPreference(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("pref_key"),
            rs.getString("value"),
            rs.getInt("value_schema_version"),
            instant(rs, "updated_at"),
            rs.getInt("row_version"));
  }

  private static RowMapper<SavedFilter> filterMapper() {
    return (ResultSet rs, int row) ->
        new SavedFilter(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("screen_code"),
            rs.getString("filter_name"),
            rs.getString("criteria"),
            rs.getBoolean("is_default"),
            rs.getInt("display_order"),
            instant(rs, "updated_at"),
            rs.getInt("row_version"));
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.from(i == null ? Instant.now() : i);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
