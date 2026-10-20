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
  public int countAt(UUID unitId, TemporalQuery query) {
    Integer n = jdbc.sql("SELECT count(*) FROM org.assignment a WHERE a.tenant_id = :tenant "
            + "AND a.org_unit_id = :unit AND a.valid_period @> CAST(:d AS date) AND " + Sql.recorded("a", query))
        .param("tenant", Sql.tenant()).param("unit", unitId).param("d", query.asOf())
        .param("rec", query.recordedAsOf())
        .query(Integer.class).single();
    return n == null ? 0 : n;
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
