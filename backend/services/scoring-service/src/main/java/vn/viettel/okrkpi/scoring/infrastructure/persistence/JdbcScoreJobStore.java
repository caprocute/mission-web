package vn.viettel.okrkpi.scoring.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.scoring.domain.ScoreJobStatus;

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

  /** Tác vụ còn lô chưa xong (DV-07-06) thì chưa kết thúc dù đã đủ tổng hiện biết. */
  private static final String PENDING_CHUNKS =
      "SELECT 1 FROM scoring.score_job_chunk c WHERE c.period_bucket = score_job.period_bucket"
          + " AND c.tenant_id = score_job.tenant_id AND c.score_job_id = score_job.id"
          + " AND c.status IN ('CHO', 'DANG_CHAY')";

  private static final String PROGRESS_SQL =
      """
      UPDATE scoring.score_job
         SET done_items = done_items + :done,
             failed_items = failed_items + :failed,
             status = CASE WHEN status = 'LOI' THEN 'LOI'
                           WHEN done_items + :done + failed_items + :failed >= total_items
                                AND NOT EXISTS (PENDING_CHUNKS) THEN 'XONG' ELSE 'DANG_CHAY' END,
             started_at = coalesce(started_at, now()),
             finished_at = CASE WHEN status = 'LOI' THEN finished_at
                                WHEN done_items + :done + failed_items + :failed >= total_items
                                     AND NOT EXISTS (PENDING_CHUNKS) THEN now() END,
             updated_at = now(),
             updated_by = :actor,
             row_version = row_version + 1
       WHERE period_bucket = :bucket AND tenant_id = :tenant AND id = :id
      """
          .replace("PENDING_CHUNKS", PENDING_CHUNKS);

  /**
   * Bảng phân vùng LIST theo {@code period_bucket} của schema {@code scoring} còn thiếu phân vùng
   * của quý (CORE-08-02, NEN-033: không có phân vùng mặc định).
   */
  private static final String MISSING_PARTITIONS =
      """
      SELECT c.oid::regclass::text FROM pg_partitioned_table pt
        JOIN pg_class c ON c.oid = pt.partrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = pt.partattrs[0]
       WHERE n.nspname = 'scoring' AND NOT c.relispartition AND pt.partstrat = 'l'
         AND pt.partnatts = 1 AND a.attname = 'period_bucket'
         AND NOT EXISTS (SELECT 1 FROM pg_class p WHERE p.relnamespace = c.relnamespace
                          AND p.relname = c.relname || '_p' || :bucket)
       ORDER BY 1
      """;

  private final JdbcClient jdbc;

  public JdbcScoreJobStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public UUID open(ScoreJob job) {
    ensurePartitions(job.periodBucket());
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

  @Override
  public void addTotal(UUID tenantId, int periodBucket, UUID jobId, int items) {
    if (items == 0) {
      return;
    }
    update(
        "UPDATE scoring.score_job SET total_items = total_items + :items, updated_at = now(),"
            + " row_version = row_version + 1",
        tenantId,
        periodBucket,
        jobId,
        Map.of("items", items));
  }

  @Override
  public void markFailed(UUID tenantId, int periodBucket, UUID jobId, UUID actorId) {
    update(
        "UPDATE scoring.score_job SET status = 'LOI', finished_at = now(), updated_at = now(),"
            + " updated_by = :actor, row_version = row_version + 1",
        tenantId,
        periodBucket,
        jobId,
        Map.of("actor", actorId));
  }

  @Override
  public Optional<Pending> pendingIncremental(
      UUID tenantId, int periodBucket, UUID periodId, String reason) {
    return jdbc.sql(
            "SELECT id, scope::text AS scope FROM scoring.score_job WHERE period_bucket = :bucket"
                + " AND tenant_id = :tenant AND period_id = :period AND job_kind = 'TAM_TINH'"
                + " AND status = 'CHO' AND scope->>'ly_do' = :reason"
                + " ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED")
        .param("bucket", periodBucket)
        .param("tenant", tenantId)
        .param("period", periodId)
        .param("reason", reason)
        .query((rs, n) -> new Pending(rs.getObject("id", UUID.class), rs.getString("scope")))
        .optional();
  }

  @Override
  public void updateScope(
      UUID tenantId, int periodBucket, UUID jobId, CanonicalDoc scope, UUID actorId) {
    update(
        "UPDATE scoring.score_job SET scope = CAST(:scope AS jsonb), updated_at = now(),"
            + " updated_by = :actor, row_version = row_version + 1",
        tenantId,
        periodBucket,
        jobId,
        Map.of("scope", scope.json(), "actor", actorId));
  }

  @Override
  public Optional<JobState> find(UUID jobId) {
    return jdbc.sql(
            "SELECT id, period_bucket, job_kind, status, total_items, done_items, failed_items,"
                + " created_by, started_at, updated_at FROM scoring.score_job WHERE id = :id")
        .param("id", jobId)
        .query(
            (rs, n) ->
                new JobState(
                    rs.getObject("id", UUID.class),
                    rs.getInt("period_bucket"),
                    ScoreJobKind.valueOf(rs.getString("job_kind")),
                    ScoreJobStatus.valueOf(rs.getString("status")),
                    rs.getInt("total_items"),
                    rs.getInt("done_items"),
                    rs.getInt("failed_items"),
                    rs.getObject("created_by", UUID.class),
                    instant(rs.getTimestamp("started_at")),
                    instant(rs.getTimestamp("updated_at"))))
        .optional();
  }

  private void update(
      String set, UUID tenantId, int periodBucket, UUID jobId, Map<String, Object> params) {
    var spec =
        jdbc.sql(set + " WHERE period_bucket = :bucket AND tenant_id = :tenant AND id = :id")
            .param("bucket", periodBucket)
            .param("tenant", tenantId)
            .param("id", jobId);
    for (Map.Entry<String, Object> p : params.entrySet()) {
      spec = spec.param(p.getKey(), p.getValue());
    }
    if (spec.update() != 1) {
      throw new IllegalStateException("Không tìm thấy tác vụ tính " + jobId);
    }
  }

  private static Instant instant(java.sql.Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  /**
   * Mọi lệnh ghi của dịch vụ đi sau một tác vụ tính, nên mở tác vụ là lúc bảo đảm có phân vùng cho
   * quý của kỳ: gọi {@code scoring.fn_create_period_partitions} (SECURITY DEFINER, {@code
   * svc_scoring} chỉ có EXECUTE) cho bảng còn thiếu; đã đủ thì chỉ tốn một truy vấn catalog. Kỳ mới
   * tạo ở mission-core chưa từng được dịch vụ này biết tới cho tới tác vụ tính đầu tiên.
   */
  void ensurePartitions(int bucket) {
    for (String table :
        jdbc.sql(MISSING_PARTITIONS).param("bucket", bucket).query(String.class).list()) {
      jdbc.sql("SELECT scoring.fn_create_period_partitions(CAST(:t AS regclass), :bucket)")
          .param("t", table)
          .param("bucket", bucket)
          .query(Integer.class)
          .single();
    }
  }
}
