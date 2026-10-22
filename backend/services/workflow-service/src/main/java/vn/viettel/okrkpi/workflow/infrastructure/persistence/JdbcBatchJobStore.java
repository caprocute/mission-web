package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.workflow.application.port.BatchJobStore;
import vn.viettel.okrkpi.workflow.domain.BatchJob;
import vn.viettel.okrkpi.workflow.domain.Decision;

/** {@link BatchJobStore} trên bảng bổ sung {@code flowable.wf_task_batch}. */
@Repository
public class JdbcBatchJobStore implements BatchJobStore {

  private static final TypeReference<List<ItemJson>> ITEMS = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public JdbcBatchJobStore(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void create(BatchJob job) {
    TenantBinding.bind(jdbc, job.tenantId());
    jdbc.update(
        "INSERT INTO flowable.wf_task_batch (id, tenant_id, decision, status, total_items,"
            + " done_items, failed_items, results_json, created_at, created_by, updated_at,"
            + " updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
        job.id(),
        job.tenantId(),
        job.decision().name(),
        job.status().name(),
        job.totalItems(),
        job.doneItems(),
        job.failedItems(),
        write(job.results()),
        Timestamp.from(job.updatedAt()),
        job.requestedBy(),
        Timestamp.from(job.updatedAt()),
        job.requestedBy());
  }

  @Override
  public void update(BatchJob job) {
    TenantBinding.bind(jdbc, job.tenantId());
    jdbc.update(
        "UPDATE flowable.wf_task_batch SET status = ?, done_items = ?, failed_items = ?,"
            + " results_json = ?::jsonb, updated_at = ?, updated_by = ?,"
            + " row_version = row_version + 1 WHERE tenant_id = ? AND id = ?",
        job.status().name(),
        job.doneItems(),
        job.failedItems(),
        write(job.results()),
        Timestamp.from(job.updatedAt()),
        job.requestedBy(),
        job.tenantId(),
        job.id());
  }

  @Override
  public Optional<BatchJob> find(UUID tenantId, UUID jobId) {
    TenantBinding.bind(jdbc, tenantId);
    return jdbc
        .query(
            "SELECT id, tenant_id, created_by, decision, status, total_items, done_items,"
                + " failed_items, results_json::text AS results, updated_at"
                + " FROM flowable.wf_task_batch WHERE tenant_id = ? AND id = ?",
            this::map,
            tenantId,
            jobId)
        .stream()
        .findFirst();
  }

  private BatchJob map(ResultSet rs, int rowNum) throws SQLException {
    List<BatchJob.ItemResult> results =
        read(rs.getString("results")).stream()
            .map(i -> new BatchJob.ItemResult(i.id(), i.status(), i.code()))
            .toList();
    return new BatchJob(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("created_by", UUID.class),
        Decision.valueOf(rs.getString("decision")),
        BatchJob.Status.valueOf(rs.getString("status")),
        rs.getInt("total_items"),
        rs.getInt("done_items"),
        rs.getInt("failed_items"),
        results,
        rs.getTimestamp("updated_at").toInstant());
  }

  private String write(List<BatchJob.ItemResult> results) {
    try {
      return json.writeValueAsString(
          results.stream().map(r -> new ItemJson(r.taskId(), r.status(), r.code())).toList());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không tuần tự hoá được kết quả lô", e);
    }
  }

  private List<ItemJson> read(String value) {
    try {
      return json.readValue(value, ITEMS);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Kết quả lô trong CSDL sai dạng", e);
    }
  }

  /** Dạng lưu của một mục, đúng tên thuộc tính {@code BatchItemResult} của hợp đồng. */
  record ItemJson(String id, int status, String code) {}
}
