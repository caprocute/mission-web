package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.CustomRoleRepository;
import vn.viettel.okrkpi.config.domain.CustomRole;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.custom_role} bằng JDBC (CORE-08-18). */
public class JdbcCustomRoleRepository implements CustomRoleRepository {

  private static final TypeReference<LinkedHashMap<String, String>> PERMS =
      new TypeReference<>() {};
  private static final String COLUMNS =
      "id, code, name, description, base_role, permissions::text AS permissions, status, created_at,"
          + " updated_at, retired_at, row_version";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcCustomRoleRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public List<CustomRole> list(TenantId tenantId, CustomRole.Status status) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.custom_role WHERE tenant_id = :tenant"
                + " AND (CAST(:status AS varchar) IS NULL OR status = :status) ORDER BY name, id")
        .param("tenant", tenantId.value())
        .param("status", status == null ? null : status.name())
        .query(this::map)
        .list();
  }

  @Override
  public Optional<CustomRole> find(TenantId tenantId, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM config.custom_role WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", tenantId.value())
        .param("id", id)
        .query(this::map)
        .optional();
  }

  @Override
  public Optional<CustomRole> findByCode(TenantId tenantId, String code) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.custom_role WHERE tenant_id = :tenant AND code = :code")
        .param("tenant", tenantId.value())
        .param("code", code)
        .query(this::map)
        .optional();
  }

  @Override
  public boolean codeTaken(String code) {
    // RLS chỉ cho thấy dòng của tenant hiện hành; mã chứa 8 ký tự đầu của tenant nên trùng chéo
    // tenant rất hiếm và
    // ràng buộc UNIQUE (code) vẫn chặn ở CSDL.
    Integer n =
        jdbc.sql("SELECT count(*) FROM config.custom_role WHERE code = :code")
            .param("code", code)
            .query(Integer.class)
            .single();
    return n != null && n > 0;
  }

  @Override
  public boolean nameTaken(TenantId tenantId, String name, UUID exceptId) {
    Integer n =
        jdbc.sql(
                "SELECT count(*) FROM config.custom_role WHERE tenant_id = :tenant"
                    + " AND lower(name) = lower(:name) AND (CAST(:except AS uuid) IS NULL OR id <> :except)")
            .param("tenant", tenantId.value())
            .param("name", name)
            .param("except", exceptId)
            .query(Integer.class)
            .single();
    return n != null && n > 0;
  }

  @Override
  public void insert(TenantId tenantId, CustomRole r, UUID actorId) {
    jdbc.sql(
            """
            INSERT INTO config.custom_role
              (id, tenant_id, created_by, updated_by, row_version, code, name, description, base_role,
               permissions, status, created_at, updated_at)
            VALUES (:id, :tenant, :actor, :actor, 0, :code, :name, :description, :base,
                    CAST(:permissions AS jsonb), :status, :created, :created)
            """)
        .param("id", r.id())
        .param("tenant", tenantId.value())
        .param("actor", actorId)
        .param("code", r.code())
        .param("name", r.name())
        .param("description", r.description())
        .param("base", r.baseRole())
        .param("permissions", write(r.permissions()))
        .param("status", r.status().name())
        .param("created", ts(r.createdAt()))
        .update();
  }

  @Override
  public boolean update(
      TenantId tenantId, CustomRole r, int expectedVersion, UUID actorId, String retireReason) {
    return jdbc.sql(
                """
                UPDATE config.custom_role SET name = :name, description = :description,
                  permissions = CAST(:permissions AS jsonb), status = :status, retired_at = :retiredAt,
                  retired_by = CASE WHEN :status = 'NGUNG' THEN :actor ELSE retired_by END,
                  retire_reason = coalesce(:reason, retire_reason),
                  updated_at = now(), updated_by = :actor, row_version = row_version + 1
                WHERE tenant_id = :tenant AND id = :id AND row_version = :expected
                """)
            .param("name", r.name())
            .param("description", r.description())
            .param("permissions", write(r.permissions()))
            .param("status", r.status().name())
            .param("retiredAt", ts(r.retiredAt()))
            .param("actor", actorId)
            .param("reason", retireReason)
            .param("tenant", tenantId.value())
            .param("id", r.id())
            .param("expected", expectedVersion)
            .update()
        == 1;
  }

  @Override
  public Map<String, Integer> holderCounts(TenantId tenantId) {
    Map<String, Integer> out = new HashMap<>();
    jdbc.sql(
            """
            SELECT role_code, count(DISTINCT person_id) AS n FROM config.role_binding
            WHERE tenant_id = :tenant AND role_code LIKE 'tc\\_%' AND end_kind IS NULL
              AND valid_from <= now() AND valid_to > now()
            GROUP BY role_code
            """)
        .param("tenant", tenantId.value())
        .query(
            (ResultSet rs) -> {
              out.put(rs.getString("role_code"), rs.getInt("n"));
            });
    return out;
  }

  private CustomRole map(ResultSet rs, int row) throws SQLException {
    return new CustomRole(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("base_role"),
        read(rs.getString("permissions")),
        CustomRole.Status.valueOf(rs.getString("status")),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")),
        instant(rs.getTimestamp("retired_at")),
        rs.getInt("row_version"));
  }

  private Map<String, String> read(String text) {
    try {
      return json.readValue(text, PERMS);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Quyền của vai trò tuỳ chỉnh không đọc được", e);
    }
  }

  private String write(Map<String, String> permissions) {
    try {
      return json.writeValueAsString(new LinkedHashMap<>(permissions));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không ghi được quyền của vai trò tuỳ chỉnh", e);
    }
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
