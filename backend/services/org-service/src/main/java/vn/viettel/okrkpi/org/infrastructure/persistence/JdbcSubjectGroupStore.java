package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.SubjectGroupRecord;
import vn.viettel.okrkpi.org.application.port.SubjectGroupStore;

/** Adapter {@code org.subject_group}. */
@Repository
public class JdbcSubjectGroupStore implements SubjectGroupStore {

  private final JdbcClient jdbc;

  public JdbcSubjectGroupStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<SubjectGroupRecord> list(LocalDate asOf) {
    return jdbc.sql("SELECT id, code, name, flow, description, valid_from, nullif(valid_to, 'infinity'::date) AS valid_to "
            + "FROM org.subject_group WHERE tenant_id = :tenant AND (CAST(:d AS date) IS NULL "
            + "OR daterange(valid_from, valid_to, '[)') @> CAST(:d AS date)) ORDER BY code")
        .param("tenant", Sql.tenant()).param("d", asOf)
        .query((rs, n) -> new SubjectGroupRecord(Sql.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
            rs.getString("flow"), rs.getString("description"), Sql.date(rs, "valid_from"), Sql.date(rs, "valid_to")))
        .list();
  }

  @Override
  public boolean isValid(UUID id, LocalDate day) {
    return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM org.subject_group WHERE tenant_id = :tenant "
            + "AND id = :id AND daterange(valid_from, valid_to, '[)') @> CAST(:d AS date))")
        .param("tenant", Sql.tenant()).param("id", id).param("d", day)
        .query(Boolean.class).single());
  }

  @Override
  public Map<UUID, String> names(Collection<UUID> ids) {
    Map<UUID, String> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    jdbc.sql("SELECT id, name FROM org.subject_group WHERE tenant_id = :tenant AND id = ANY(CAST(:ids AS uuid[]))")
        .param("tenant", Sql.tenant()).param("ids", Sql.uuidArray(ids))
        .query((rs, n) -> out.put(Sql.uuid(rs, "id"), rs.getString("name"))).list();
    return out;
  }
}
