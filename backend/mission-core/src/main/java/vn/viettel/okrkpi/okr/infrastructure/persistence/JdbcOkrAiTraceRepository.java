package vn.viettel.okrkpi.okr.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import vn.viettel.okrkpi.okr.application.port.OkrAiTraceRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-01-13: dấu vết nội dung AI trên {@code okrDataSource} (RLS theo tenant). */
public class JdbcOkrAiTraceRepository implements OkrAiTraceRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcOkrAiTraceRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void record(TenantId tenant, int bucket, List<AiTrace> traces) {
    for (AiTrace t : traces) {
      jdbc.update(
          "INSERT INTO okr.ai_content_trace (id, created_by, updated_by, objective_id, version_no,"
              + " key_result_id, suggestion_id, content_source, kept_fields, edited_fields)"
              + " SELECT :id, :actor, :actor, o.id, o.version_no, :kr, :s, :src,"
              + " CAST(:kept AS varchar(32)[]), CAST(:edited AS varchar(32)[])"
              + " FROM okr.objective o WHERE o.period_bucket = :b AND o.tenant_id = :t"
              + " AND o.id = :o",
          new MapSqlParameterSource("t", tenant.value())
              .addValue("b", bucket)
              .addValue("o", t.objectiveId())
              .addValue("id", UUID.randomUUID())
              .addValue("actor", t.actor())
              .addValue("kr", t.keyResultId())
              .addValue("s", t.suggestionId())
              .addValue("src", t.contentSource())
              .addValue("kept", t.keptFields().toArray(String[]::new))
              .addValue("edited", t.editedFields().toArray(String[]::new)));
    }
  }

  @Override
  public void refreshObjectiveAiAssisted(TenantId tenant, int bucket, UUID objectiveId) {
    jdbc.update(
        "UPDATE okr.objective o SET ai_assisted = (o.ai_suggestion_id IS NOT NULL OR EXISTS ("
            + " SELECT 1 FROM okr.key_result k WHERE k.period_bucket = o.period_bucket"
            + " AND k.tenant_id = o.tenant_id AND k.objective_id = o.id"
            + " AND k.content_source <> 'HUMAN'))"
            + " WHERE o.period_bucket = :b AND o.tenant_id = :t AND o.id = :o",
        new MapSqlParameterSource("t", tenant.value())
            .addValue("b", bucket)
            .addValue("o", objectiveId));
  }

  @Override
  public List<AiTrace> traces(TenantId tenant, UUID objectiveId) {
    return jdbc.query(
        "SELECT objective_id, key_result_id, suggestion_id, content_source, kept_fields,"
            + " edited_fields, created_at, created_by, version_no FROM okr.ai_content_trace"
            + " WHERE tenant_id = :t AND objective_id = :o ORDER BY created_at, id",
        new MapSqlParameterSource("t", tenant.value()).addValue("o", objectiveId),
        JdbcOkrAiTraceRepository::row);
  }

  private static AiTrace row(ResultSet rs, int i) throws SQLException {
    return new AiTrace(
        rs.getObject("objective_id", UUID.class),
        rs.getObject("key_result_id", UUID.class),
        rs.getObject("suggestion_id", UUID.class),
        rs.getString("content_source"),
        strings(rs.getArray("kept_fields")),
        strings(rs.getArray("edited_fields")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getObject("created_by", UUID.class),
        rs.getInt("version_no"));
  }

  private static List<String> strings(Array a) throws SQLException {
    return a == null ? List.of() : List.of((String[]) a.getArray());
  }
}
