package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.scoring.port.IncidentRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@code appraisal.incident} trên {@code appraisalDataSource} (CORE-04-13). Phạm vi đơn vị đọc cây
 * đơn vị của bản sao tổ chức {@code org_replica.unit_closure_v} ở cả hai cây.
 */
public class JdbcIncidentRepository implements IncidentRepository {

  private static final String COLUMNS =
      "id, person_id, period_id, org_unit_id, incident_type, concluded_on, document_no,"
          + " document_ref, impact, summary_enc, dek_id, created_by, created_at, row_version";

  private static final String WITHIN =
      "(org_unit_id = ? OR org_unit_id IN (SELECT u.descendant_id FROM"
          + " org_replica.unit_closure_v u WHERE u.tenant_id = ? AND u.ancestor_id = ?))";

  private final JdbcClient jdbc;

  public JdbcIncidentRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void insert(TenantId tenant, IncidentRow row) {
    SealedText summary = row.summary();
    jdbc.sql(
            "INSERT INTO appraisal.incident (id, tenant_id, created_at, created_by, updated_at,"
                + " updated_by, person_id, period_id, org_unit_id, incident_type, concluded_on,"
                + " document_no, document_ref, impact, summary_enc, dek_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            row.id(),
            tenant.value(),
            Timestamp.from(row.createdAt()),
            row.createdBy(),
            Timestamp.from(row.createdAt()),
            row.createdBy(),
            row.personId(),
            row.periodId(),
            row.orgUnitId(),
            row.incidentType(),
            Date.valueOf(row.concludedOn()),
            row.documentNo(),
            row.documentRef(),
            row.impact(),
            summary == null ? null : summary.cipher(),
            summary == null ? null : summary.dekId())
        .update();
  }

  @Override
  public Optional<IncidentRow> find(TenantId tenant, UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM appraisal.incident WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query(JdbcIncidentRepository::row)
        .optional();
  }

  @Override
  public List<IncidentRow> list(
      TenantId tenant, UUID unitScope, UUID personId, UUID periodId, UUID beforeId, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT " + COLUMNS + " FROM appraisal.incident WHERE tenant_id = ?");
    List<Object> params = new ArrayList<>(List.of(tenant.value()));
    sql.append(" AND ").append(WITHIN);
    params.addAll(List.of(unitScope, tenant.value(), unitScope));
    if (personId != null) {
      sql.append(" AND person_id = ?");
      params.add(personId);
    }
    if (periodId != null) {
      sql.append(" AND period_id = ?");
      params.add(periodId);
    }
    if (beforeId != null) {
      sql.append(" AND id < ?");
      params.add(beforeId);
    }
    sql.append(" ORDER BY id DESC LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query(JdbcIncidentRepository::row).list();
  }

  @Override
  public boolean withinUnit(TenantId tenant, UUID rootUnitId, UUID unitId) {
    if (rootUnitId.equals(unitId)) {
      return true;
    }
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM org_replica.unit_closure_v WHERE tenant_id = ?"
                + " AND ancestor_id = ? AND descendant_id = ?)")
        .params(tenant.value(), rootUnitId, unitId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public Map<UUID, List<IncidentRow>> forPersons(
      TenantId tenant, UUID periodId, LocalDate from, LocalDate to, Collection<UUID> personIds) {
    Map<UUID, List<IncidentRow>> out = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM appraisal.incident WHERE tenant_id = ? AND person_id = ANY(?::uuid[])"
                + " AND (period_id = ? OR (period_id IS NULL AND CAST(? AS date) IS NOT NULL"
                + " AND concluded_on BETWEEN CAST(? AS date) AND CAST(? AS date)))"
                + " ORDER BY person_id, concluded_on, id")
        .params(
            tenant.value(),
            personIds.toArray(UUID[]::new),
            periodId,
            from == null ? null : Date.valueOf(from),
            from == null ? null : Date.valueOf(from),
            to == null ? null : Date.valueOf(to))
        .query(
            rs -> {
              IncidentRow r = row(rs, 0);
              out.computeIfAbsent(r.personId(), k -> new ArrayList<>()).add(r);
            });
    return out;
  }

  @Override
  public Optional<PersonProfile> personProfile(TenantId tenant, UUID personId, UUID periodId) {
    String sql =
        "SELECT period_id, flow, subject_group_id, org_unit_id FROM appraisal.appraisal_profile"
            + " WHERE tenant_id = ? AND person_id = ?"
            + (periodId == null ? "" : " AND period_id = ?")
            + " ORDER BY period_bucket DESC, created_at DESC LIMIT 1";
    List<Object> params = new ArrayList<>(List.of(tenant.value(), personId));
    if (periodId != null) {
      params.add(periodId);
    }
    return jdbc.sql(sql)
        .params(params)
        .query(
            (rs, i) ->
                new PersonProfile(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getObject(3, UUID.class),
                    rs.getObject(4, UUID.class)))
        .optional();
  }

  private static IncidentRow row(ResultSet rs, int i) throws SQLException {
    byte[] cipher = rs.getBytes("summary_enc");
    return new IncidentRow(
        rs.getObject("id", UUID.class),
        rs.getObject("person_id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getObject("org_unit_id", UUID.class),
        rs.getString("incident_type"),
        rs.getObject("concluded_on", LocalDate.class),
        rs.getString("document_no"),
        rs.getString("document_ref"),
        rs.getString("impact"),
        cipher == null ? null : new SealedText(cipher, rs.getObject("dek_id", UUID.class)),
        rs.getObject("created_by", UUID.class),
        rs.getTimestamp("created_at").toInstant(),
        rs.getInt("row_version"));
  }
}
