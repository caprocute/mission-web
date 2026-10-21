package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.port.DiscrepancyStore;

/** DV-04-07 · {@code org.sync_discrepancy}; cột {@code _enc} chỉ đi qua dạng bản mã. */
@Repository
public class JdbcDiscrepancyStore implements DiscrepancyStore {

  private static final String COLS = "id, person_id, field, source_connector, dek_id, source_value_enc, "
      + "current_value_enc, severity, suggested_action, resolution, resolved_by, resolved_at, sync_run_id, "
      + "external_id, effective_from, matched_by, assignee_id, note, source_fingerprint, row_version";

  private final JdbcClient jdbc;

  public JdbcDiscrepancyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Row> findLatestActive(String connector, String externalId, String field) {
    return jdbc.sql("SELECT " + COLS + " FROM org.sync_discrepancy WHERE tenant_id = :tenant "
            + "AND source_connector = :c AND external_id = :e AND field = :f "
            + "AND (resolution IS NULL OR resolution = 'GIU_HIEN_TAI') ORDER BY id DESC LIMIT 1 FOR UPDATE")
        .param("tenant", Sql.tenant()).param("c", connector).param("e", externalId).param("f", field)
        .query(JdbcDiscrepancyStore::map).optional();
  }

  @Override
  public void insert(Row r, Actor actor) {
    jdbc.sql("INSERT INTO org.sync_discrepancy (id, tenant_id, person_id, field, source_connector, source_value_enc, "
            + "current_value_enc, dek_id, severity, suggested_action, sync_run_id, external_id, effective_from, "
            + "matched_by, source_fingerprint, created_by, updated_by) VALUES (:id, :tenant, :person, :field, :c, "
            + ":src, :cur, :dek, :sev, :act, :run, :ext, :eff, :by, :fp, :actor, :actor)")
        .param("id", r.id()).param("tenant", Sql.tenant()).param("person", r.personId()).param("field", r.field())
        .param("c", r.connector()).param("src", r.sourceEnc()).param("cur", r.currentEnc()).param("dek", r.dekId())
        .param("sev", r.severity()).param("act", r.suggestedAction()).param("run", r.syncRunId())
        .param("ext", r.externalId()).param("eff", r.effectiveFrom()).param("by", r.matchedBy())
        .param("fp", r.fingerprint()).param("actor", actor.actorId())
        .update();
  }

  @Override
  public void refresh(Row r, Actor actor) {
    jdbc.sql("UPDATE org.sync_discrepancy SET person_id = :person, source_value_enc = :src, current_value_enc = :cur, "
            + "dek_id = :dek, severity = :sev, suggested_action = :act, sync_run_id = :run, effective_from = :eff, "
            + "matched_by = :by, source_fingerprint = :fp, updated_at = now(), updated_by = :actor, "
            + "row_version = row_version + 1 WHERE tenant_id = :tenant AND id = :id AND resolution IS NULL")
        .param("person", r.personId()).param("src", r.sourceEnc()).param("cur", r.currentEnc())
        .param("dek", r.dekId()).param("sev", r.severity()).param("act", r.suggestedAction())
        .param("run", r.syncRunId()).param("eff", r.effectiveFrom()).param("by", r.matchedBy())
        .param("fp", r.fingerprint()).param("actor", actor.actorId()).param("tenant", Sql.tenant())
        .param("id", r.id())
        .update();
  }

  @Override
  public List<Row> findForUpdate(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("SELECT " + COLS + " FROM org.sync_discrepancy WHERE tenant_id = :tenant "
            + "AND id = ANY(CAST(:ids AS uuid[])) ORDER BY id FOR UPDATE")
        .param("tenant", Sql.tenant()).param("ids", Sql.uuidArray(ids))
        .query(JdbcDiscrepancyStore::map).list();
  }

  @Override
  public void resolve(UUID id, String resolution, String note, Actor actor) {
    jdbc.sql("UPDATE org.sync_discrepancy SET resolution = :res, resolved_by = :actor, resolved_at = now(), "
            + "note = coalesce(:note, note), updated_at = now(), updated_by = :actor, row_version = row_version + 1 "
            + "WHERE tenant_id = :tenant AND id = :id")
        .param("res", resolution).param("actor", actor.actorId()).param("note", note).param("tenant", Sql.tenant())
        .param("id", id)
        .update();
  }

  @Override
  public void assign(UUID id, UUID assigneeId, String note, Actor actor) {
    jdbc.sql("UPDATE org.sync_discrepancy SET assignee_id = :a, note = coalesce(:note, note), updated_at = now(), "
            + "updated_by = :actor, row_version = row_version + 1 WHERE tenant_id = :tenant AND id = :id")
        .param("a", assigneeId).param("note", note).param("actor", actor.actorId()).param("tenant", Sql.tenant())
        .param("id", id)
        .update();
  }

  @Override
  public void mapPerson(UUID id, UUID personId, Actor actor) {
    jdbc.sql("UPDATE org.sync_discrepancy SET person_id = :p, matched_by = 'ANH_XA', updated_at = now(), "
            + "updated_by = :actor, row_version = row_version + 1 WHERE tenant_id = :tenant AND id = :id")
        .param("p", personId).param("actor", actor.actorId()).param("tenant", Sql.tenant()).param("id", id)
        .update();
  }

  @Override
  public List<Row> page(String field, String severity, boolean unresolved, UUID after, int limit) {
    StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM org.sync_discrepancy WHERE tenant_id = :tenant");
    Map<String, Object> p = new HashMap<>();
    p.put("tenant", Sql.tenant());
    filters(sql, p, field, severity, unresolved);
    if (after != null) {
      sql.append(" AND id < :after");
      p.put("after", after);
    }
    sql.append(" ORDER BY id DESC LIMIT :limit");
    p.put("limit", limit);
    return jdbc.sql(sql.toString()).params(p).query(JdbcDiscrepancyStore::map).list();
  }

  @Override
  public Map<String, Long> countBySeverity(String field, boolean unresolved) {
    return count("severity", field, null, unresolved);
  }

  @Override
  public Map<String, Long> countByField(String severity, boolean unresolved) {
    return count("field", null, severity, unresolved);
  }

  private Map<String, Long> count(String by, String field, String severity, boolean unresolved) {
    StringBuilder sql = new StringBuilder("SELECT " + by + " AS k, count(*) AS n FROM org.sync_discrepancy "
        + "WHERE tenant_id = :tenant");
    Map<String, Object> p = new HashMap<>();
    p.put("tenant", Sql.tenant());
    filters(sql, p, field, severity, unresolved);
    sql.append(" GROUP BY ").append(by);
    Map<String, Long> out = new LinkedHashMap<>();
    jdbc.sql(sql.toString()).params(p).query((rs, n) -> out.put(rs.getString("k"), rs.getLong("n"))).list();
    return out;
  }

  private static void filters(StringBuilder sql, Map<String, Object> p, String field, String severity,
      boolean unresolved) {
    if (field != null) {
      sql.append(" AND field = :field");
      p.put("field", field);
    }
    if (severity != null) {
      sql.append(" AND severity = :severity");
      p.put("severity", severity);
    }
    if (unresolved) {
      sql.append(" AND resolution IS NULL");
    }
  }

  @Override
  public long countOpenHigh() {
    Long n = jdbc.sql("SELECT count(*) FROM org.sync_discrepancy WHERE tenant_id = :tenant AND severity = 'CAO' "
            + "AND resolution IS NULL")
        .param("tenant", Sql.tenant()).query(Long.class).single();
    return n == null ? 0 : n;
  }

  @Override
  public Map<UUID, String> unitNames(Collection<UUID> ids) {
    return names("SELECT DISTINCT ON (id) id, name FROM org.org_unit WHERE tenant_id = :tenant "
        + "AND id = ANY(CAST(:ids AS uuid[])) AND recorded_to = 'infinity' ORDER BY id, valid_from DESC", ids);
  }

  @Override
  public Map<UUID, String> jobTitleNames(Collection<UUID> ids) {
    return names("SELECT DISTINCT ON (id) id, name FROM org.job_title WHERE tenant_id = :tenant "
        + "AND id = ANY(CAST(:ids AS uuid[])) ORDER BY id, valid_from DESC", ids);
  }

  private Map<UUID, String> names(String sql, Collection<UUID> ids) {
    Map<UUID, String> out = new HashMap<>();
    jdbc.sql(sql).param("tenant", Sql.tenant()).param("ids", Sql.uuidArray(ids.stream().filter(i -> i != null)
            .toList()))
        .query((rs, n) -> out.put(Sql.uuid(rs, "id"), rs.getString("name"))).list();
    return out;
  }

  private static Row map(ResultSet rs, int n) throws SQLException {
    return new Row(Sql.uuid(rs, "id"), Sql.uuid(rs, "person_id"), rs.getString("field"),
        rs.getString("source_connector"), Sql.uuid(rs, "dek_id"), rs.getBytes("source_value_enc"),
        rs.getBytes("current_value_enc"), rs.getString("severity"), rs.getString("suggested_action"),
        rs.getString("resolution"), Sql.uuid(rs, "resolved_by"), Sql.ts(rs, "resolved_at"),
        Sql.uuid(rs, "sync_run_id"), rs.getString("external_id"), Sql.date(rs, "effective_from"),
        rs.getString("matched_by"), Sql.uuid(rs, "assignee_id"), rs.getString("note"),
        rs.getBytes("source_fingerprint"), rs.getInt("row_version"));
  }
}
