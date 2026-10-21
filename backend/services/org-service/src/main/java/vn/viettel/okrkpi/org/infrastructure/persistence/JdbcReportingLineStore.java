package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.TemporalQuery;
import vn.viettel.okrkpi.org.application.port.ReportingLineStore;
import vn.viettel.okrkpi.org.domain.ReportingLineVersion;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.viettel.okrkpi.org.domain.VersionPlan;

/** Adapter {@code org.reporting_line}. */
@Repository
public class JdbcReportingLineStore implements ReportingLineStore {

  private static final String COLS = "r.version_id, r.id, r.subordinate_person_id, r.superior_person_id, "
      + "r.line_type, r.branch, " + Sql.axes("r");

  private final JdbcClient jdbc;
  private final BitemporalWriter writer;
  private final Clock clock;

  public JdbcReportingLineStore(JdbcClient jdbc, BitemporalWriter writer, Clock clock) {
    this.jdbc = jdbc;
    this.writer = writer;
    this.clock = clock;
  }

  @Override
  public List<ReportingLineVersion> currentForSubordinateForUpdate(UUID subordinatePersonId) {
    return jdbc.sql("SELECT " + COLS + " FROM org.reporting_line r WHERE r.tenant_id = :tenant "
            + "AND r.subordinate_person_id = :subId AND r.recorded_to = 'infinity' ORDER BY r.valid_from FOR UPDATE")
        .param("tenant", Sql.tenant()).param("subId", subordinatePersonId)
        .query(JdbcReportingLineStore::map).list();
  }

  @Override
  public List<ReportingLineVersion> currentVersionsForUpdate(UUID reportingLineId) {
    return jdbc.sql("SELECT " + COLS + " FROM org.reporting_line r WHERE r.tenant_id = :tenant "
            + "AND r.id = :id AND r.recorded_to = 'infinity' ORDER BY r.valid_from FOR UPDATE")
        .param("tenant", Sql.tenant()).param("id", reportingLineId)
        .query(JdbcReportingLineStore::map).list();
  }

  @Override
  public List<ReportingLineVersion> findBySubordinateAt(UUID subordinatePersonId, TemporalQuery query) {
    return jdbc.sql("SELECT " + COLS + " FROM org.reporting_line r WHERE r.tenant_id = :tenant "
            + "AND r.subordinate_person_id = :subId AND r.valid_period @> CAST(:d AS date) AND "
            + Sql.recorded("r", query) + " ORDER BY r.line_type, r.branch")
        .param("tenant", Sql.tenant()).param("subId", subordinatePersonId)
        .param("d", query.asOf()).param("rec", query.recordedAsOf())
        .query(JdbcReportingLineStore::map).list();
  }

  @Override
  public void apply(VersionPlan<ReportingLineVersion> plan, UUID changeOrderId, Actor actor) {
    writer.close("reporting_line", plan.toClose(), actor);
    Map<UUID, Integer> next = writer.nextVersions("reporting_line",
        plan.toInsert().stream().map(ReportingLineVersion::id).distinct().toList());
    for (ReportingLineVersion v : plan.toInsert()) {
      UUID co = changeOrderId != null ? changeOrderId : v.changeOrderId();
      jdbc.sql("INSERT INTO org.reporting_line (version_id, id, tenant_id, subordinate_person_id, superior_person_id, "
              + "line_type, branch, change_order_id, valid_from, valid_to, created_by, updated_by, row_version) "
              + "VALUES (:vid, :id, :tenant, :sub, :sup, :lt, :branch, :co, :f, "
              + "coalesce(CAST(:t AS date), 'infinity'::date), :actor, :actor, :rv)")
          .param("vid", UuidV7.next(clock)).param("id", v.id()).param("tenant", Sql.tenant())
          .param("sub", v.subordinatePersonId()).param("sup", v.superiorPersonId())
          .param("lt", v.lineType()).param("branch", v.branch()).param("co", co)
          .param("f", v.validPeriod().from()).param("t", v.validPeriod().to())
          .param("actor", actor.actorId()).param("rv", next.get(v.id()))
          .update();
    }
  }

  static ReportingLineVersion map(ResultSet rs, int n) throws SQLException {
    return new ReportingLineVersion(
        Sql.uuid(rs, "version_id"),
        Sql.uuid(rs, "id"),
        Sql.uuid(rs, "subordinate_person_id"),
        Sql.uuid(rs, "superior_person_id"),
        rs.getString("line_type"),
        rs.getString("branch"),
        Sql.uuid(rs, "change_order_id"),
        Sql.period(rs),
        Sql.ts(rs, "recorded_from"),
        Sql.ts(rs, "recorded_to"),
        rs.getInt("row_version"));
  }
}
