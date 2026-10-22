package vn.viettel.okrkpi.config.infrastructure.persistence;

import static vn.viettel.okrkpi.config.infrastructure.persistence.JdbcRoleBindingRepository.instant;
import static vn.viettel.okrkpi.config.infrastructure.persistence.JdbcRoleBindingRepository.ts;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.config.application.port.DelegationRepository;
import vn.viettel.okrkpi.config.domain.Delegation;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kho {@code config.delegation} bằng JDBC (CORE-08-16). */
public class JdbcDelegationRepository implements DelegationRepository {

  /** Trạng thái suy ra, tham số {@code :now}. */
  static final String STATUS =
      "CASE WHEN ended_at IS NOT NULL THEN 'DA_THU_HOI'"
          + " WHEN :now < valid_from THEN 'SAP_HIEU_LUC'"
          + " WHEN :now < valid_to THEN 'HIEU_LUC' ELSE 'HET_HAN' END";

  private static final String COLUMNS =
      "id, kind, delegator_id, delegate_id, org_unit_id, subject_id, valid_from, valid_to, reason,"
          + " created_by, created_at, ended_by, ended_at, end_reason, row_version";

  private final JdbcClient jdbc;

  public JdbcDelegationRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void insert(TenantId tenantId, Delegation d, UUID actorId) {
    jdbc.sql(
            """
            INSERT INTO config.delegation
              (id, tenant_id, created_at, created_by, updated_by, row_version, kind, delegator_id,
               delegate_id, org_unit_id, subject_id, valid_from, valid_to, reason)
            VALUES (:id, :tenant, :createdAt, :actor, :actor, :version, :kind, :delegator,
                    :delegate, :unit, :subject, :from, :to, :reason)
            """)
        .param("id", d.id())
        .param("tenant", tenantId.value())
        .param("createdAt", ts(d.createdAt()))
        .param("actor", actorId)
        .param("version", d.rowVersion())
        .param("kind", d.kind().name())
        .param("delegator", d.delegatorId())
        .param("delegate", d.delegateId())
        .param("unit", d.orgUnitId())
        .param("subject", d.subjectId())
        .param("from", ts(d.validFrom()))
        .param("to", ts(d.validTo()))
        .param("reason", d.reason())
        .update();
  }

  @Override
  public Optional<Delegation> find(TenantId tenantId, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM config.delegation WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", tenantId.value())
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  @Override
  public boolean update(TenantId tenantId, Delegation d, int expectedVersion, UUID actorId) {
    return jdbc.sql(
                """
                UPDATE config.delegation
                   SET valid_from = :from, valid_to = :to, ended_by = :endedBy, ended_at = :endedAt,
                       end_reason = :endReason, row_version = :version, updated_at = now(),
                       updated_by = :actor
                 WHERE tenant_id = :tenant AND id = :id AND row_version = :expected
                """)
            .param("from", ts(d.validFrom()))
            .param("to", ts(d.validTo()))
            .param("endedBy", d.endedBy())
            .param("endedAt", ts(d.endedAt()))
            .param("endReason", d.endReason())
            .param("version", d.rowVersion())
            .param("actor", actorId)
            .param("tenant", tenantId.value())
            .param("id", d.id())
            .param("expected", expectedVersion)
            .update()
        == 1;
  }

  @Override
  public void lockKey(TenantId tenantId, UUID delegatorId, Delegation.Kind kind) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", "config.delegation:" + tenantId.value() + ":" + delegatorId + ":" + kind)
        .query()
        .singleValue();
  }

  @Override
  public List<Delegation> overlapping(
      TenantId tenantId,
      Delegation.Kind kind,
      UUID delegatorId,
      UUID delegateId,
      Instant from,
      Instant to) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.delegation WHERE tenant_id = :tenant AND kind = :kind"
                + " AND delegator_id = :delegator AND delegate_id = :delegate AND ended_at IS NULL"
                + " AND valid_from < :to AND valid_to > :from ORDER BY valid_from, id")
        .param("tenant", tenantId.value())
        .param("kind", kind.name())
        .param("delegator", delegatorId)
        .param("delegate", delegateId)
        .param("from", ts(from))
        .param("to", ts(to))
        .query(MAPPER)
        .list();
  }

  @Override
  public List<Delegation> receivedDuring(
      TenantId tenantId, Delegation.Kind kind, UUID delegateId, Instant from, Instant to) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.delegation WHERE tenant_id = :tenant AND kind = :kind"
                + " AND delegate_id = :delegate AND ended_at IS NULL"
                + " AND valid_from < :to AND valid_to > :from ORDER BY valid_from, id")
        .param("tenant", tenantId.value())
        .param("kind", kind.name())
        .param("delegate", delegateId)
        .param("from", ts(from))
        .param("to", ts(to))
        .query(MAPPER)
        .list();
  }

  @Override
  public List<Delegation> list(TenantId tenantId, Filter f, Instant now, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT " + COLUMNS + " FROM config.delegation WHERE tenant_id = :tenant");
    if (!f.units().isEmpty()) {
      sql.append(" AND org_unit_id IN (:units)");
    }
    if (f.personId() != null) {
      sql.append(" AND (delegator_id = :person OR delegate_id = :person)");
    }
    if (f.kind() != null) {
      sql.append(" AND kind = :kind");
    }
    if (f.status() != null) {
      sql.append(" AND (").append(STATUS).append(") = :status");
    }
    if (afterId != null) {
      sql.append(" AND id < :after");
    }
    sql.append(" ORDER BY id DESC LIMIT :limit");
    JdbcClient.StatementSpec spec =
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
    if (f.kind() != null) {
      spec = spec.param("kind", f.kind().name());
    }
    if (f.status() != null) {
      spec = spec.param("status", f.status().name());
    }
    if (afterId != null) {
      spec = spec.param("after", afterId);
    }
    return spec.query(MAPPER).list();
  }

  @Override
  public List<Delegation> dueExpiry(TenantId tenantId, Instant now, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM config.delegation WHERE tenant_id = :tenant AND ended_at IS NULL"
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
                "UPDATE config.delegation SET expiry_emitted_at = :at, row_version = row_version + 1 WHERE tenant_id = :tenant"
                    + " AND id = :id AND expiry_emitted_at IS NULL")
            .param("at", ts(at))
            .param("tenant", tenantId.value())
            .param("id", id)
            .update()
        == 1;
  }

  @Override
  public List<Delegation> history(
      TenantId tenantId, UUID personId, Collection<UUID> units, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT " + COLUMNS + " FROM config.delegation WHERE tenant_id = :tenant");
    if (personId != null) {
      sql.append(" AND (delegator_id = :person OR delegate_id = :person)");
    }
    if (!units.isEmpty()) {
      sql.append(" AND org_unit_id IN (:units)");
    }
    sql.append(" ORDER BY greatest(created_at, coalesce(ended_at, created_at)) DESC, id DESC")
        .append(" LIMIT :limit");
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

  private static final RowMapper<Delegation> MAPPER =
      (ResultSet rs, int row) ->
          new Delegation(
              rs.getObject("id", UUID.class),
              Delegation.Kind.valueOf(rs.getString("kind")),
              rs.getObject("delegator_id", UUID.class),
              rs.getObject("delegate_id", UUID.class),
              rs.getObject("org_unit_id", UUID.class),
              rs.getObject("subject_id", UUID.class),
              instant(rs, "valid_from"),
              instant(rs, "valid_to"),
              rs.getString("reason"),
              rs.getObject("created_by", UUID.class),
              instant(rs, "created_at"),
              rs.getObject("ended_by", UUID.class),
              instant(rs, "ended_at"),
              rs.getString("end_reason"),
              rs.getInt("row_version"));
}
