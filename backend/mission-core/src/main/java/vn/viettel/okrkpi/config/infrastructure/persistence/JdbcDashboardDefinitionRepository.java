package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.DashboardDefinitionRepository;
import vn.viettel.okrkpi.config.domain.DashboardDefinition;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kho {@code config.dashboard_def} bằng JDBC (CORE-08-17). Mảng chỉ số và chiều lưu ở cột {@code
 * jsonb}, đọc ra dạng {@code text[]}; đơn vị chia sẻ là {@code uuid[]}, lọc phạm vi {@code DON_VI}
 * bằng toán tử giao {@code &&}.
 */
public class JdbcDashboardDefinitionRepository implements DashboardDefinitionRepository {

  private static final String COLUMNS =
      "id, owner_user_id, title,"
          + " ARRAY(SELECT jsonb_array_elements_text(measures)) AS measures,"
          + " ARRAY(SELECT jsonb_array_elements_text(dimensions)) AS dimensions,"
          + " layout_json::text AS layout_json, share_scope, shared_unit_ids, updated_at,"
          + " row_version";

  private final JdbcClient jdbc;

  public JdbcDashboardDefinitionRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<DashboardDefinition> find(TenantId tenantId, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM config.dashboard_def WHERE tenant_id = ? AND id = ?")
        .param(tenantId.value())
        .param(id)
        .query(mapper())
        .optional();
  }

  @Override
  public List<DashboardDefinition> visible(
      TenantId tenantId,
      UUID userId,
      List<UUID> viewerUnits,
      boolean ownOnly,
      UUID beforeId,
      int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + """
                 FROM config.dashboard_def
                WHERE tenant_id = ?
                  AND (owner_user_id = ?
                       OR (NOT ? AND (share_scope = 'TENANT'
                                      OR (share_scope = 'DON_VI'
                                          AND shared_unit_ids && ?::uuid[]))))
                  AND (?::uuid IS NULL OR id < ?::uuid)
                ORDER BY id DESC
                LIMIT ?
                """)
        .param(tenantId.value())
        .param(userId)
        .param(ownOnly)
        .param(uuidArray(viewerUnits))
        .param(beforeId)
        .param(beforeId)
        .param(limit)
        .query(mapper())
        .list();
  }

  @Override
  public boolean titleTaken(TenantId tenantId, UUID ownerUserId, String title, UUID exceptId) {
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM config.dashboard_def
                            WHERE tenant_id = ? AND owner_user_id = ? AND lower(title) = lower(?)
                              AND (?::uuid IS NULL OR id <> ?::uuid))
            """)
        .param(tenantId.value())
        .param(ownerUserId)
        .param(title)
        .param(exceptId)
        .param(exceptId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public void lockOwner(TenantId tenantId, UUID ownerUserId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param("config.dashboard_def:" + tenantId.value() + ":" + ownerUserId)
        .query()
        .singleValue();
  }

  @Override
  public void insert(TenantId tenantId, DashboardDefinition d, UUID actorId) {
    Timestamp now = ts(d.updatedAt());
    jdbc.sql(
            """
            INSERT INTO config.dashboard_def
              (id, tenant_id, created_at, created_by, updated_at, updated_by, row_version,
               owner_user_id, title, measures, dimensions, layout_json, share_scope,
               shared_unit_ids)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, to_jsonb(?::text[]), to_jsonb(?::text[]),
                    ?::jsonb, ?, ?::uuid[])
            """)
        .param(d.id())
        .param(tenantId.value())
        .param(now)
        .param(actorId)
        .param(now)
        .param(actorId)
        .param(d.rowVersion())
        .param(d.ownerUserId())
        .param(d.title())
        .param(textArray(d.measures()))
        .param(textArray(d.dimensions()))
        .param(d.layoutJson())
        .param(d.shareScope().name())
        .param(uuidArray(d.sharedUnitIds()))
        .update();
  }

  @Override
  public boolean update(
      TenantId tenantId, DashboardDefinition d, int expectedVersion, UUID actorId) {
    return jdbc.sql(
                """
                UPDATE config.dashboard_def
                   SET title = ?, measures = to_jsonb(?::text[]), dimensions = to_jsonb(?::text[]),
                       layout_json = ?::jsonb, share_scope = ?, shared_unit_ids = ?::uuid[],
                       row_version = ?, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND id = ? AND owner_user_id = ? AND row_version = ?
                """)
            .param(d.title())
            .param(textArray(d.measures()))
            .param(textArray(d.dimensions()))
            .param(d.layoutJson())
            .param(d.shareScope().name())
            .param(uuidArray(d.sharedUnitIds()))
            .param(d.rowVersion())
            .param(ts(d.updatedAt()))
            .param(actorId)
            .param(tenantId.value())
            .param(d.id())
            .param(d.ownerUserId())
            .param(expectedVersion)
            .update()
        == 1;
  }

  private static RowMapper<DashboardDefinition> mapper() {
    return (ResultSet rs, int row) ->
        new DashboardDefinition(
            rs.getObject("id", UUID.class),
            rs.getObject("owner_user_id", UUID.class),
            rs.getString("title"),
            strings(rs.getArray("measures")),
            strings(rs.getArray("dimensions")),
            rs.getString("layout_json"),
            DashboardDefinition.ShareScope.valueOf(rs.getString("share_scope")),
            uuids(rs.getArray("shared_unit_ids")),
            instant(rs, "updated_at"),
            rs.getInt("row_version"));
  }

  private static List<String> strings(Array a) throws SQLException {
    return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
  }

  private static List<UUID> uuids(Array a) throws SQLException {
    return a == null ? List.of() : Arrays.asList((UUID[]) a.getArray());
  }

  /** Mảng PostgreSQL dạng chữ; phần tử luôn được bọc nháy kép và thoát ký tự đặc biệt. */
  private static String textArray(List<String> values) {
    return values.stream()
        .map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        .collect(Collectors.joining(",", "{", "}"));
  }

  private static String uuidArray(List<UUID> values) {
    return values.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.from(i);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
