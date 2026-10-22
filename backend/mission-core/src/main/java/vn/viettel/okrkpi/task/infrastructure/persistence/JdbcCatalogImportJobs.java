package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.catalog.CatalogImportJobs;
import vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob;
import vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob.ErrorGroup;
import vn.viettel.okrkpi.task.domain.catalog.CatalogImportJob.ErrorRow;

/**
 * {@link CatalogImportJobs} trên bảng {@code task.catalog_import_job}; nhóm lỗi, dòng lỗi là jsonb.
 */
public class JdbcCatalogImportJobs implements CatalogImportJobs {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<List<ErrorGroup>> GROUPS = new TypeReference<>() {};
  private static final TypeReference<List<ErrorRow>> ROWS = new TypeReference<>() {};

  private static final String COLUMNS =
      "id, work_catalog_id, target_catalog_id, file_name, status, total_rows, valid_rows,"
          + " error_groups::text AS error_groups, failure_detail, created_at, finished_at";

  private final JdbcClient jdbc;

  public JdbcCatalogImportJobs(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(TenantId tenant, UUID actor, CatalogImportJob job) {
    jdbc.sql(
            """
            INSERT INTO task.catalog_import_job
              (id, tenant_id, created_at, created_by, updated_by, work_catalog_id, target_catalog_id,
               file_name, status)
            VALUES (:id, :t, :at, :a, :a, :catalog, :target, :file, :status)
            """)
        .param("id", job.id())
        .param("t", tenant.value())
        .param("at", Timestamp.from(job.createdAt()))
        .param("a", actor)
        .param("catalog", job.catalogId())
        .param("target", job.targetCatalogId())
        .param("file", job.fileName())
        .param("status", job.status().name())
        .update();
  }

  @Override
  public void finish(
      TenantId tenant, UUID actor, CatalogImportJob result, List<ErrorRow> errorRows) {
    jdbc.sql(
            """
            UPDATE task.catalog_import_job
               SET status = :status, total_rows = :total, valid_rows = :valid,
                   error_groups = CAST(:groups AS jsonb), error_rows = CAST(:rows AS jsonb),
                   failure_detail = :detail, finished_at = :done,
                   updated_at = now(), updated_by = :a, row_version = row_version + 1
             WHERE tenant_id = :t AND id = :id AND status = 'DANG_XU_LY'
            """)
        .param("status", result.status().name())
        .param("total", result.totalRows())
        .param("valid", result.validRows())
        .param("groups", write(result.errorGroups()))
        .param("rows", write(errorRows))
        .param("detail", result.failureDetail())
        .param("done", Timestamp.from(result.finishedAt()))
        .param("a", actor)
        .param("t", tenant.value())
        .param("id", result.id())
        .update();
  }

  @Override
  public Optional<CatalogImportJob> find(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM task.catalog_import_job WHERE tenant_id = :t AND id = :id")
        .param("t", tenant.value())
        .param("id", id)
        .query(JdbcCatalogImportJobs::map)
        .optional();
  }

  @Override
  public Optional<CatalogImportJob> latest(TenantId tenant, UUID catalogId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM task.catalog_import_job WHERE tenant_id = :t AND work_catalog_id = :c"
                + " ORDER BY created_at DESC, id DESC LIMIT 1")
        .param("t", tenant.value())
        .param("c", catalogId)
        .query(JdbcCatalogImportJobs::map)
        .optional();
  }

  @Override
  public List<ErrorRow> errorRows(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT error_rows::text FROM task.catalog_import_job WHERE tenant_id = :t AND id = :id")
        .param("t", tenant.value())
        .param("id", id)
        .query(String.class)
        .optional()
        .map(s -> read(s, ROWS))
        .orElse(List.of());
  }

  private static CatalogImportJob map(ResultSet rs, int n) throws SQLException {
    Timestamp done = rs.getTimestamp("finished_at");
    return new CatalogImportJob(
        rs.getObject("id", UUID.class),
        rs.getObject("work_catalog_id", UUID.class),
        rs.getObject("target_catalog_id", UUID.class),
        rs.getString("file_name"),
        CatalogImportJob.Status.valueOf(rs.getString("status")),
        rs.getInt("total_rows"),
        rs.getInt("valid_rows"),
        read(rs.getString("error_groups"), GROUPS),
        rs.getString("failure_detail"),
        rs.getTimestamp("created_at").toInstant(),
        done == null ? null : done.toInstant());
  }

  private static String write(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không ghi được jsonb lần nhập", e);
    }
  }

  private static <T> T read(String json, TypeReference<T> type) {
    try {
      return JSON.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không đọc được jsonb lần nhập", e);
    }
  }
}
