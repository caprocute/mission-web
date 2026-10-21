package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.TemporalQuery;
import vn.viettel.okrkpi.org.application.port.AssignmentStore;
import vn.viettel.okrkpi.org.domain.AssignmentVersion;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.viettel.okrkpi.org.domain.VersionPlan;

/** Adapter {@code org.assignment}. */
@Repository
public class JdbcAssignmentStore implements AssignmentStore {

  private static final String COLS = "a.version_id, a.id, a.person_id, a.org_unit_id, a.position_id, a.job_title_id, "
      + "a.kind, a.branch, a.is_head, " + Sql.axes("a");

  private final JdbcClient jdbc;
  private final BitemporalWriter writer;
  private final Clock clock;

  public JdbcAssignmentStore(JdbcClient jdbc, BitemporalWriter writer, Clock clock) {
    this.jdbc = jdbc;
    this.writer = writer;
    this.clock = clock;
  }

  @Override
  public List<AssignmentVersion> currentAtUnitsFromForUpdate(Collection<UUID> unitIds, LocalDate from) {
    if (unitIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("SELECT " + COLS + " FROM org.assignment a WHERE a.tenant_id = :tenant "
            + "AND a.org_unit_id = ANY(CAST(:ids AS uuid[])) AND a.recorded_to = 'infinity' "
            + "AND a.valid_period && daterange(CAST(:f AS date), NULL, '[)') ORDER BY a.id, a.valid_from FOR UPDATE")
        .param("tenant", Sql.tenant()).param("ids", Sql.uuidArray(unitIds)).param("f", from)
        .query(JdbcAssignmentStore::map).list();
  }

  @Override
  public List<AssignmentVersion> currentVersions(UUID assignmentId) {
    return jdbc.sql("SELECT " + COLS + " FROM org.assignment a WHERE a.tenant_id = :tenant AND a.id = :id "
            + "AND a.recorded_to = 'infinity' ORDER BY a.valid_from")
        .param("tenant", Sql.tenant()).param("id", assignmentId)
        .query(JdbcAssignmentStore::map).list();
  }

  @Override
  public List<AssignmentVersion> currentVersionsForUpdate(UUID assignmentId) {
    return jdbc.sql("SELECT " + COLS + " FROM org.assignment a WHERE a.tenant_id = :tenant AND a.id = :id "
            + "AND a.recorded_to = 'infinity' ORDER BY a.valid_from FOR UPDATE")
        .param("tenant", Sql.tenant()).param("id", assignmentId)
        .query(JdbcAssignmentStore::map).list();
  }

  @Override
  public List<AssignmentVersion> currentForPersonForUpdate(UUID personId) {
    return jdbc.sql("SELECT " + COLS + " FROM org.assignment a WHERE a.tenant_id = :tenant AND a.person_id = :person "
            + "AND a.recorded_to = 'infinity' ORDER BY a.valid_from FOR UPDATE")
        .param("tenant", Sql.tenant()).param("person", personId)
        .query(JdbcAssignmentStore::map).list();
  }

  @Override
  public List<AssignmentVersion> findCurrentByPersonAndBranch(UUID personId, String branch) {
    return jdbc.sql("SELECT " + COLS + " FROM org.assignment a WHERE a.tenant_id = :tenant AND a.person_id = :person "
            + "AND a.branch = :branch AND a.recorded_to = 'infinity' ORDER BY a.valid_from, a.row_version")
        .param("tenant", Sql.tenant()).param("person", personId).param("branch", branch)
        .query(JdbcAssignmentStore::map).list();
  }

  @Override
  public int countAt(UUID unitId, TemporalQuery query) {
    Integer n = jdbc.sql("SELECT count(*) FROM org.assignment a WHERE a.tenant_id = :tenant "
            + "AND a.org_unit_id = :unit AND a.valid_period @> CAST(:d AS date) AND " + Sql.recorded("a", query))
        .param("tenant", Sql.tenant()).param("unit", unitId).param("d", query.asOf())
        .param("rec", query.recordedAsOf())
        .query(Integer.class).single();
    return n == null ? 0 : n;
  }

  @Override
  public boolean personExists(UUID personId, TemporalQuery query) {
    Boolean ok = jdbc.sql("SELECT EXISTS(SELECT 1 FROM org.person p WHERE p.tenant_id = :tenant AND p.id = :id AND "
            + Sql.recorded("p", query) + ")")
        .param("tenant", Sql.tenant()).param("id", personId).param("rec", query.recordedAsOf())
        .query(Boolean.class).single();
    return Boolean.TRUE.equals(ok);
  }

  @Override
  public List<PersonAssignmentRow> findByPersonAt(UUID personId, TemporalQuery query) {
    String sql = "SELECT a.id, a.org_unit_id, coalesce(u.name, '') AS org_unit_name, "
        + "coalesce(jt.name, 'Chuyên viên') AS job_title_name, a.kind, a.branch, a.is_head, "
        + "a.valid_from, nullif(a.valid_to, 'infinity'::date) AS valid_to "
        + "FROM org.assignment a "
        + "LEFT JOIN LATERAL (SELECT u1.name FROM org.org_unit u1 WHERE u1.tenant_id = a.tenant_id "
        + "  AND u1.id = a.org_unit_id AND " + Sql.recorded("u1", query)
        + "  ORDER BY (u1.valid_period @> CAST(:d AS date)) DESC, u1.valid_from DESC LIMIT 1) u ON true "
        + "LEFT JOIN org.job_title jt ON jt.tenant_id = a.tenant_id AND jt.id = a.job_title_id "
        + "WHERE a.tenant_id = :tenant AND a.person_id = :person "
        + "AND a.valid_period @> CAST(:d AS date) AND " + Sql.recorded("a", query)
        + " ORDER BY (CASE WHEN a.kind = 'CHINH' THEN 0 ELSE 1 END), a.valid_from DESC, a.id";
    return jdbc.sql(sql)
        .param("tenant", Sql.tenant()).param("person", personId)
        .param("d", query.asOf()).param("rec", query.recordedAsOf())
        .query((rs, n) -> new PersonAssignmentRow(
            Sql.uuid(rs, "id"),
            Sql.uuid(rs, "org_unit_id"),
            rs.getString("org_unit_name"),
            rs.getString("job_title_name"),
            rs.getString("kind"),
            rs.getString("branch"),
            rs.getBoolean("is_head"),
            Sql.date(rs, "valid_from"),
            Sql.date(rs, "valid_to")))
        .list();
  }

  @Override
  public List<UnitAssignmentRow> findByUnitAt(UUID unitId, TemporalQuery query) {
    String sql = "SELECT a.id, a.person_id, coalesce(p.full_name, '') AS person_name, "
        + "coalesce(jt.name, 'Chuyên viên') AS job_title_name, a.kind, a.branch, "
        + "a.valid_from, nullif(a.valid_to, 'infinity'::date) AS valid_to "
        + "FROM org.assignment a "
        + "LEFT JOIN LATERAL (SELECT p1.full_name FROM org.person p1 WHERE p1.tenant_id = a.tenant_id "
        + "  AND p1.id = a.person_id AND " + Sql.recorded("p1", query)
        + "  ORDER BY (p1.valid_period @> CAST(:d AS date)) DESC, p1.valid_from DESC LIMIT 1) p ON true "
        + "LEFT JOIN org.job_title jt ON jt.tenant_id = a.tenant_id AND jt.id = a.job_title_id "
        + "WHERE a.tenant_id = :tenant AND a.org_unit_id = :unit "
        + "AND a.valid_period @> CAST(:d AS date) AND " + Sql.recorded("a", query)
        + " ORDER BY p.full_name, a.id";
    return jdbc.sql(sql)
        .param("tenant", Sql.tenant()).param("unit", unitId)
        .param("d", query.asOf()).param("rec", query.recordedAsOf())
        .query((rs, n) -> new UnitAssignmentRow(
            Sql.uuid(rs, "id"),
            Sql.uuid(rs, "person_id"),
            rs.getString("person_name"),
            rs.getString("job_title_name"),
            rs.getString("kind"),
            rs.getString("branch"),
            Sql.date(rs, "valid_from"),
            Sql.date(rs, "valid_to")))
        .list();
  }

  @Override
  public List<PersonSummaryRow> findPersonsInUnitsAt(Collection<UUID> unitIds, TemporalQuery query) {
    if (unitIds.isEmpty()) {
      return List.of();
    }
    String sql = "SELECT DISTINCT ON (a.person_id) a.person_id, p.full_name, "
        + "coalesce(jt.name, 'Chuyên viên') AS job_title_name, a.org_unit_id, coalesce(u.name, '') AS unit_name "
        + "FROM org.assignment a "
        + "JOIN LATERAL (SELECT p1.full_name, p1.employment_status FROM org.person p1 "
        + "  WHERE p1.tenant_id = a.tenant_id AND p1.id = a.person_id AND " + Sql.recorded("p1", query)
        + "  ORDER BY (p1.valid_period @> CAST(:d AS date)) DESC, p1.valid_from DESC LIMIT 1) p "
        + "  ON p.employment_status = 'DANG_CONG_TAC' "
        + "LEFT JOIN LATERAL (SELECT u1.name FROM org.org_unit u1 WHERE u1.tenant_id = a.tenant_id "
        + "  AND u1.id = a.org_unit_id AND " + Sql.recorded("u1", query)
        + "  ORDER BY (u1.valid_period @> CAST(:d AS date)) DESC, u1.valid_from DESC LIMIT 1) u ON true "
        + "LEFT JOIN org.job_title jt ON jt.tenant_id = a.tenant_id AND jt.id = a.job_title_id "
        + "WHERE a.tenant_id = :tenant AND a.org_unit_id = ANY(CAST(:units AS uuid[])) "
        + "AND a.valid_period @> CAST(:d AS date) AND " + Sql.recorded("a", query)
        + " ORDER BY a.person_id, (CASE WHEN a.kind = 'CHINH' THEN 0 ELSE 1 END), a.valid_from DESC";
    return jdbc.sql(sql)
        .param("tenant", Sql.tenant()).param("units", Sql.uuidArray(unitIds))
        .param("d", query.asOf()).param("rec", query.recordedAsOf())
        .query((rs, n) -> new PersonSummaryRow(
            Sql.uuid(rs, "person_id"),
            rs.getString("full_name"),
            rs.getString("job_title_name"),
            Sql.uuid(rs, "org_unit_id"),
            rs.getString("unit_name")))
        .list();
  }

  @Override
  public void apply(VersionPlan<AssignmentVersion> plan, UUID changeOrderId, Actor actor) {
    writer.close("assignment", plan.toClose(), actor);
    Map<UUID, Integer> next = writer.nextVersions("assignment",
        plan.toInsert().stream().map(AssignmentVersion::id).distinct().toList());
    for (AssignmentVersion v : plan.toInsert()) {
      UUID co = changeOrderId != null ? changeOrderId : v.changeOrderId();
      jdbc.sql("INSERT INTO org.assignment (version_id, id, tenant_id, person_id, org_unit_id, position_id, "
              + "job_title_id, kind, branch, is_head, change_order_id, valid_from, valid_to, created_by, updated_by, "
              + "row_version) VALUES (:vid, :id, :tenant, :person, :unit, :pos, :title, :kind, :branch, :head, :co, "
              + ":f, coalesce(CAST(:t AS date), 'infinity'::date), :actor, :actor, :rv)")
          .param("vid", UuidV7.next(clock)).param("id", v.id()).param("tenant", Sql.tenant())
          .param("person", v.personId()).param("unit", v.orgUnitId()).param("pos", v.positionId())
          .param("title", v.jobTitleId()).param("kind", v.kind()).param("branch", v.branch())
          .param("head", v.head()).param("co", co).param("f", v.validPeriod().from())
          .param("t", v.validPeriod().to()).param("actor", actor.actorId()).param("rv", next.get(v.id()))
          .update();
    }
  }

  static AssignmentVersion map(ResultSet rs, int n) throws SQLException {
    return new AssignmentVersion(Sql.uuid(rs, "version_id"), Sql.uuid(rs, "id"), Sql.uuid(rs, "person_id"),
        Sql.uuid(rs, "org_unit_id"), Sql.uuid(rs, "position_id"), Sql.uuid(rs, "job_title_id"), rs.getString("kind"),
        rs.getString("branch"), rs.getBoolean("is_head"), Sql.uuid(rs, "change_order_id"), Sql.period(rs),
        Sql.ts(rs, "recorded_from"), Sql.ts(rs, "recorded_to"), rs.getInt("row_version"));
  }
}
