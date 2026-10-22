package vn.viettel.okrkpi.org.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.orgimport.ImportError;
import vn.viettel.okrkpi.org.application.port.ImportJobStore;

/** DV-04-05 · {@code org.import_job}. */
@Repository
public class JdbcImportJobStore implements ImportJobStore {

  private static final TypeReference<List<ImportError>> ERRORS = new TypeReference<>() {
  };

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcImportJobStore(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void insert(NewJob job, Actor actor) {
    jdbc.sql("INSERT INTO org.import_job (id, tenant_id, file_name, file_content, file_sha256, effective_from, "
            + "created_by, updated_by) VALUES (:id, :tenant, :name, :content, :sha, :eff, :actor, :actor)")
        .param("id", job.id()).param("tenant", Sql.tenant()).param("name", job.fileName())
        .param("content", job.content()).param("sha", job.sha256()).param("eff", job.effectiveFrom())
        .param("actor", actor.actorId())
        .update();
  }

  @Override
  public Optional<JobRow> find(UUID id) {
    return jdbc.sql("SELECT id, created_by, status, progress, message, effective_from, total_rows, created_units, "
            + "errors::text AS errors, change_order_id, updated_at FROM org.import_job "
            + "WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", Sql.tenant()).param("id", id).query(this::map).optional();
  }

  @Override
  public Optional<byte[]> content(UUID id) {
    return jdbc.sql("SELECT file_content FROM org.import_job WHERE tenant_id = :tenant AND id = :id "
            + "AND file_content IS NOT NULL")
        .param("tenant", Sql.tenant()).param("id", id).query((rs, n) -> rs.getBytes(1)).optional();
  }

  @Override
  public void progress(UUID id, String status, int progress, String message, int totalRows, Actor actor) {
    jdbc.sql("UPDATE org.import_job SET status = :s, progress = :p, message = :m, total_rows = :rows, "
            + "started_at = COALESCE(started_at, now()), updated_at = now(), updated_by = :actor, "
            + "row_version = row_version + 1 WHERE tenant_id = :tenant AND id = :id")
        .param("s", status).param("p", progress).param("m", message).param("rows", totalRows)
        .param("actor", actor.actorId()).param("tenant", Sql.tenant()).param("id", id)
        .update();
  }

  @Override
  public void finish(UUID id, String status, int totalRows, int createdUnits, List<ImportError> errors,
      UUID changeOrderId, String message, Actor actor) {
    jdbc.sql("UPDATE org.import_job SET status = :s, progress = 100, message = :m, total_rows = :rows, "
            + "created_units = :created, error_count = :ec, errors = CAST(:errors AS jsonb), change_order_id = :co, "
            + "file_content = NULL, finished_at = now(), updated_at = now(), updated_by = :actor, "
            + "row_version = row_version + 1 WHERE tenant_id = :tenant AND id = :id")
        .param("s", status).param("m", message).param("rows", totalRows).param("created", createdUnits)
        .param("ec", errors.size()).param("errors", write(errors)).param("co", changeOrderId)
        .param("actor", actor.actorId()).param("tenant", Sql.tenant()).param("id", id)
        .update();
  }

  private String write(List<ImportError> errors) {
    try {
      return json.writeValueAsString(errors);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private JobRow map(ResultSet rs, int n) throws SQLException {
    List<ImportError> errors;
    try {
      errors = json.readValue(rs.getString("errors"), ERRORS);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    return new JobRow(rs.getObject("id", UUID.class), rs.getObject("created_by", UUID.class), rs.getString("status"),
        rs.getInt("progress"), rs.getString("message"), rs.getObject("effective_from", LocalDate.class),
        rs.getInt("total_rows"), rs.getInt("created_units"), errors, rs.getObject("change_order_id", UUID.class),
        rs.getObject("updated_at", OffsetDateTime.class));
  }
}
