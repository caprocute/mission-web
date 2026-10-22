package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.RoleBindingRepository;
import vn.viettel.okrkpi.config.domain.RoleBinding;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.role_binding} bằng JDBC (CORE-08-16). */
public class JdbcRoleBindingRepository implements RoleBindingRepository {

  static final String SENSITIVE = "('decider', 'second_approver', 'policy_author')";

  /** Trạng thái suy ra (quyết định WEB-058 điểm 2), tham số {@code :now}. */
  static final String STATUS =
      "CASE WHEN end_kind = 'TU_CHOI' THEN 'TU_CHOI'"
          + " WHEN end_kind = 'THU_HOI' THEN 'DA_THU_HOI'"
          + " WHEN approved_by IS NULL AND role_code IN "
          + SENSITIVE
          + " THEN 'CHO_DUYET'"
          + " WHEN :now < valid_from THEN 'SAP_HIEU_LUC'"
          + " WHEN :now < valid_to THEN 'HIEU_LUC' ELSE 'HET_HAN' END";

  private static final String COLUMNS =
      "id, person_id, role_code, org_unit_id, scope, flow, valid_from, valid_to, granted_by,"
          + " granted_at, reason, approved_by, approved_at, ended_by, ended_at, end_kind,"
          + " end_reason, row_version";

  private final JdbcClient jdbc;

  public JdbcRoleBindingRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void insert(TenantId tenantId, RoleBinding b, UUID actorId) {
    jdbc.sql(
            """
            INSERT INTO config.role_binding
              (id, tenant_id, created_by, updated_by, row_version, person_id, role_code,
               org_unit_id, scope, flow, valid_from, valid_to, granted_by, granted_at, reason)
            VALUES (:id, :tenant, :actor, :actor, :version, :person, :role, :unit, :scope, :flow,
                    :from, :to, :grantedBy, :grantedAt, :reason)
            """)
        .param("id", b.id())
        .param("tenant", tenantId.value())
        .param("actor", actorId)
        .param("version", b.rowVersion())
        .param("person", b.personId())
        .param("role", b.roleCode())
        .param("unit", b.orgUnitId())
        .param("scope", b.scope().name())
        .param("flow", b.flow())
        .param("from", ts(b.validFrom()))
        .param("to", ts(b.validTo()))
        .param("grantedBy", b.grantedBy())
        .param("grantedAt", ts(b.grantedAt()))
        .param("reason", b.reason())
        .update();
  }

  @Override
  public Optional<RoleBinding> find(TenantId tenantId, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.role_binding WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", tenantId.value())
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  @Override
  public boolean update(TenantId tenantId, RoleBinding b, int expectedVersion, UUID actorId) {
    return jdbc.sql(
                """
                UPDATE config.role_binding
                   SET valid_from = :from, valid_to = :to, approved_by = :approvedBy,
                       approved_at = :approvedAt, ended_by = :endedBy, ended_at = :endedAt,
                       end_kind = :endKind, end_reason = :endReason, row_version = :version,
                       updated_at = now(), updated_by = :actor
                 WHERE tenant_id = :tenant AND id = :id AND row_version = :expected
                """)
            .param("from", ts(b.validFrom()))
            .param("to", ts(b.validTo()))
            .param("approvedBy", b.approvedBy())
            .param("approvedAt", ts(b.approvedAt()))
            .param("endedBy", b.endedBy())
            .param("endedAt", ts(b.endedAt()))
            .param("endKind", b.endKind() == null ? null : b.endKind().name())
            .param("endReason", b.endReason())
            .param("version", b.rowVersion())
            .param("actor", actorId)
            .param("tenant", tenantId.value())
            .param("id", b.id())
            .param("expected", expectedVersion)
            .update()
        == 1;
  }

  @Override
  public void lockKey(
      TenantId tenantId, UUID personId, String roleCode, UUID orgUnitId, String scope) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param(
            "key",
            "config.role_binding:"
                + tenantId.value()
                + ":"
                + personId
                + ":"
                + roleCode
                + ":"
                + orgUnitId
                + ":"
                + scope)
        .query()
        .singleValue();
  }

  @Override
  public boolean existsOpen(
      TenantId tenantId, UUID personId, String roleCode, UUID orgUnitId, String scope, Instant at) {
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM config.role_binding
             WHERE tenant_id = :tenant AND person_id = :person AND role_code = :role
               AND org_unit_id = :unit AND scope = :scope AND end_kind IS NULL AND valid_to > :at)
            """)
        .param("tenant", tenantId.value())
        .param("person", personId)
        .param("role", roleCode)
        .param("unit", orgUnitId)
        .param("scope", scope)
        .param("at", ts(at))
        .query(Boolean.class)
        .single();
  }

  @Override
  public List<RoleBinding> list(TenantId tenantId, Filter f, Instant now, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT " + COLUMNS + " FROM config.role_binding WHERE tenant_id = :tenant");
    JdbcClient.StatementSpec spec;
    if (!f.units().isEmpty()) {
      sql.append(" AND org_unit_id IN (:units)");
    }
    if (f.personId() != null) {
      sql.append(" AND person_id = :person");
    }
    if (f.roleCode() != null) {
      sql.append(" AND role_code = :role");
    }
    if (f.status() != null) {
      sql.append(" AND (").append(STATUS).append(") = :status");
    } else {
      sql.append(" AND end_kind IS DISTINCT FROM 'TU_CHOI'");
    }
    if (f.awaitingApprover() != null) {
      sql.append(" AND end_kind IS NULL AND approved_by IS NULL AND role_code IN ")
          .append(SENSITIVE)
          .append(" AND granted_by <> :approver AND person_id <> :approver");
    }
    if (afterId != null) {
      sql.append(" AND id < :after");
    }
    sql.append(" ORDER BY id DESC LIMIT :limit");
    spec =
        jdbc.sql(sql.toString())
            .param("tenant", tenantId.value())
            .param("now", ts(now))
            .param("limit", limit);
    if (!f.units().isEmpty()) {
      spec = spec.param("units", List.copyOf(f.units()));
    }
    if (f.personId() != null) {
      spec = spec.param("person", f.personId());
    }
    if (f.roleCode() != null) {
      spec = spec.param("role", f.roleCode());
    }
    if (f.status() != null) {
      spec = spec.param("status", f.status().name());
    }
    if (f.awaitingApprover() != null) {
      spec = spec.param("approver", f.awaitingApprover());
    }
    if (afterId != null) {
      spec = spec.param("after", afterId);
    }
    return spec.query(MAPPER).list();
  }

  @Override
  public Counts counts(TenantId tenantId, Collection<UUID> units, UUID caller, Instant now) {
    String where = units.isEmpty() ? "" : " AND org_unit_id IN (:units)";
    JdbcClient.StatementSpec spec =
        jdbc.sql(
                "SELECT"
                    + " count(*) FILTER (WHERE s = 'HIEU_LUC') AS active,"
                    + " count(*) FILTER (WHERE s = 'CHO_DUYET') AS pending,"
                    + " count(*) FILTER (WHERE s = 'CHO_DUYET' AND granted_by <> :caller"
                    + "   AND person_id <> :caller) AS awaiting,"
                    + " count(*) FILTER (WHERE s = 'HIEU_LUC' AND valid_to <= :soon) AS expiring"
                    + " FROM (SELECT ("
                    + STATUS
                    + ") AS s, granted_by, person_id, valid_to FROM config.role_binding"
                    + " WHERE tenant_id = :tenant"
                    + where
                    + ") t")
            .param("tenant", tenantId.value())
            .param("now", ts(now))
            .param("soon", ts(now.plus(RoleBinding.EXPIRING_SOON)))
            .param("caller", caller);
    if (!units.isEmpty()) {
      spec = spec.param("units", List.copyOf(units));
    }
    return spec.query(
            (rs, i) ->
                new Counts(
                    rs.getInt("active"),
                    rs.getInt("pending"),
                    rs.getInt("awaiting"),
                    rs.getInt("expiring")))
        .single();
  }

  @Override
  public boolean holds(
      TenantId tenantId, UUID personId, String roleCode, Collection<UUID> units, Instant at) {
    if (units.isEmpty()) {
      return false;
    }
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM config.role_binding WHERE tenant_id = :tenant"
                + " AND person_id = :person AND role_code = :role AND org_unit_id IN (:units)"
                + " AND ("
                + STATUS
                + ") = 'HIEU_LUC')")
        .param("tenant", tenantId.value())
        .param("person", personId)
        .param("role", roleCode)
        .param("units", List.copyOf(units))
        .param("now", ts(at))
        .query(Boolean.class)
        .single();
  }

  @Override
  public List<RoleBinding> effectiveOf(TenantId tenantId, UUID personId, Instant at) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.role_binding WHERE tenant_id = :tenant AND person_id = :person"
                + " AND ("
                + STATUS
                + ") = 'HIEU_LUC' ORDER BY role_code, id")
        .param("tenant", tenantId.value())
        .param("person", personId)
        .param("now", ts(at))
        .query(MAPPER)
        .list();
  }

  @Override
  public List<UUID> persons(TenantId tenantId, UUID afterId, int limit) {
    return jdbc.sql(
            "SELECT DISTINCT person_id FROM config.role_binding WHERE tenant_id = :tenant"
                + (afterId == null ? "" : " AND person_id > :after")
                + " ORDER BY person_id LIMIT :limit")
        .params(
            afterId == null
                ? java.util.Map.of("tenant", tenantId.value(), "limit", limit)
                : java.util.Map.of("tenant", tenantId.value(), "after", afterId, "limit", limit))
        .query(UUID.class)
        .list();
  }

  @Override
  public List<RoleBinding> dueExpiry(TenantId tenantId, Instant now, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.role_binding WHERE tenant_id = :tenant AND end_kind IS NULL"
                + " AND expiry_emitted_at IS NULL AND valid_to <= :now ORDER BY valid_to, id"
                + " LIMIT :limit FOR UPDATE SKIP LOCKED")
        .param("tenant", tenantId.value())
        .param("now", ts(now))
        .param("limit", limit)
        .query(MAPPER)
        .list();
  }

  @Override
  public boolean markExpiryEmitted(TenantId tenantId, UUID id, Instant at) {
    return jdbc.sql(
                "UPDATE config.role_binding SET expiry_emitted_at = :at, row_version = row_version + 1 WHERE tenant_id = :tenant"
                    + " AND id = :id AND expiry_emitted_at IS NULL")
            .param("at", ts(at))
            .param("tenant", tenantId.value())
            .param("id", id)
            .update()
        == 1;
  }

  @Override
  public List<RoleBinding> history(
      TenantId tenantId, UUID personId, Collection<UUID> units, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT " + COLUMNS + " FROM config.role_binding WHERE tenant_id = :tenant");
    if (personId != null) {
      sql.append(" AND person_id = :person");
    }
    if (!units.isEmpty()) {
      sql.append(" AND org_unit_id IN (:units)");
    }
    sql.append(" ORDER BY greatest(granted_at, coalesce(approved_at, granted_at),")
        .append(" coalesce(ended_at, granted_at)) DESC, id DESC LIMIT :limit");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString()).param("tenant", tenantId.value()).param("limit", limit);
    if (personId != null) {
      spec = spec.param("person", personId);
    }
    if (!units.isEmpty()) {
      spec = spec.param("units", List.copyOf(units));
    }
    return spec.query(MAPPER).list();
  }

  private static final RowMapper<RoleBinding> MAPPER =
      (ResultSet rs, int row) ->
          new RoleBinding(
              rs.getObject("id", UUID.class),
              rs.getObject("person_id", UUID.class),
              rs.getString("role_code"),
              rs.getObject("org_unit_id", UUID.class),
              RoleBinding.Scope.valueOf(rs.getString("scope")),
              rs.getString("flow"),
              instant(rs, "valid_from"),
              instant(rs, "valid_to"),
              rs.getObject("granted_by", UUID.class),
              instant(rs, "granted_at"),
              rs.getString("reason"),
              rs.getObject("approved_by", UUID.class),
              instant(rs, "approved_at"),
              rs.getObject("ended_by", UUID.class),
              instant(rs, "ended_at"),
              rs.getString("end_kind") == null
                  ? null
                  : RoleBinding.EndKind.valueOf(rs.getString("end_kind")),
              rs.getString("end_reason"),
              rs.getInt("row_version"));

  static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  /**
   * Giá trị driver PostgreSQL trả cho {@code infinity} ({@code
   * PGStatement.DATE_POSITIVE_INFINITY}).
   */
  private static final long PG_POSITIVE_INFINITY = 9223372036825200000L;

  /** {@code infinity} của PostgreSQL đọc thành 9999-12-31. */
  static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    if (t == null) {
      return null;
    }
    if (t.getTime() == PG_POSITIVE_INFINITY) {
      return Instant.parse("9999-12-31T23:59:59Z");
    }
    return t.toInstant();
  }
}
