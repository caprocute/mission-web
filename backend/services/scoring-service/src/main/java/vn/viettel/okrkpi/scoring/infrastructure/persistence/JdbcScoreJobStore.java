package vn.viettel.okrkpi.scoring.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;

/** {@code scoring.score_job} qua JDBC; ngữ cảnh tenant đã đặt trên kết nối (RLS). */
public class JdbcScoreJobStore implements ScoreJobStore {

  private static final String INSERT_SQL =
      """
      INSERT INTO scoring.score_job (id, tenant_id, created_by, updated_by, period_id, period_bucket,
          job_kind, scope, policy_pack_version_id, trigger_event_id, total_items, status)
      VALUES (:id, :tenant, :actor, :actor, :period, :bucket, :kind, CAST(:scope AS jsonb), :ppv,
          :trigger, :total, 'CHO')
      ON CONFLICT (period_bucket, tenant_id, trigger_event_id) WHERE trigger_event_id IS NOT NULL
      DO NOTHING
      RETURNING id
      """;

  private static final String PROGRESS_SQL =
      """
      UPDATE scoring.score_job
         SET done_items = done_items + :done,
             failed_items = failed_items + :failed,
             status = CASE WHEN done_items + :done + failed_items + :failed >= total_items
                           THEN 'XONG' ELSE 'DANG_CHAY' END,
             started_at = coalesce(started_at, now()),
             finished_at = CASE WHEN done_items + :done + failed_items + :failed >= total_items
                                THEN now() END,
             updated_at = now(),
             updated_by = :actor,
             row_version = row_version + 1
       WHERE period_bucket = :bucket AND tenant_id = :tenant AND id = :id
      """;

  private final JdbcClient jdbc;

  public JdbcScoreJobStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public UUID open(ScoreJob job) {
    List<UUID> created =
        jdbc.sql(INSERT_SQL)
            .param("id", job.id())
            .param("tenant", job.tenantId())
            .param("actor", job.actorId())
            .param("period", job.periodId())
            .param("bucket", job.periodBucket())
            .param("kind", job.kind().name())
            .param("scope", job.scope().json())
            .param("ppv", job.policyPackVersionId())
            .param("trigger", job.triggerEventId())
            .param("total", job.totalItems())
            .query(UUID.class)
            .list();
    if (!created.isEmpty()) {
      return created.get(0);
    }
    return jdbc.sql(
            "SELECT id FROM scoring.score_job WHERE period_bucket = :bucket AND tenant_id = :tenant"
                + " AND trigger_event_id = :trigger")
        .param("bucket", job.periodBucket())
        .param("tenant", job.tenantId())
        .param("trigger", job.triggerEventId())
        .query(UUID.class)
        .single();
  }

  @Override
  public void progress(
      UUID tenantId, int periodBucket, UUID jobId, int done, int failed, UUID actorId) {
    int updated =
        jdbc.sql(PROGRESS_SQL)
            .param("done", done)
            .param("failed", failed)
            .param("actor", actorId)
            .param("bucket", periodBucket)
            .param("tenant", tenantId)
            .param("id", jobId)
            .update();
    if (updated != 1) {
      throw new IllegalStateException("Không tìm thấy tác vụ tính " + jobId);
    }
  }
}
