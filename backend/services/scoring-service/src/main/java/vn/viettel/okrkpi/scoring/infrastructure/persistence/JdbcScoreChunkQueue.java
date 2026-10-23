package vn.viettel.okrkpi.scoring.infrastructure.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.scoring.application.port.ScoreChunkQueue;
import vn.viettel.okrkpi.scoring.domain.ScoreJobError;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.shared.kernel.Ids;

/**
 * {@code scoring.score_job_chunk} và {@code scoring.score_job_error} qua JDBC (DV-07-06). Nhận lô
 * bằng {@code UPDATE} có điều kiện nên nhiều worker song song không xử lý trùng; {@code
 * lease_until} vừa là hạn thuê vừa là mốc thử lại.
 */
public class JdbcScoreChunkQueue implements ScoreChunkQueue {

  private static final int MAX_ERROR = 500;

  private static final String START_SQL =
      """
      UPDATE scoring.score_job
         SET status = 'DANG_CHAY', started_at = coalesce(started_at, now()), updated_at = now(),
             row_version = row_version + 1
       WHERE period_bucket = :bucket AND tenant_id = :tenant AND id = :id AND status = 'CHO'
      RETURNING id, period_id, job_kind, scope::text AS scope, policy_pack_version_id, created_by,
                total_items
      """;

  private static final String CLAIM_SQL =
      """
      UPDATE scoring.score_job_chunk c
         SET status = 'DANG_CHAY', attempts = c.attempts + 1,
             lease_until = now() + make_interval(secs => :lease), updated_at = now()
        FROM scoring.score_job j
       WHERE c.period_bucket = :bucket AND c.tenant_id = :tenant AND c.id = :id
         AND c.status IN ('CHO', 'DANG_CHAY') AND c.lease_until <= now()
         AND j.period_bucket = c.period_bucket AND j.tenant_id = c.tenant_id
         AND j.id = c.score_job_id
      RETURNING c.seq, c.page_cursor, c.attempts, j.id AS job_id, j.period_id, j.job_kind,
                j.scope::text AS scope, j.policy_pack_version_id, j.created_by, j.total_items
      """;

  private final JdbcClient jdbc;

  public JdbcScoreChunkQueue(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Ref> due(int limit) {
    return refs(
        "SELECT id, tenant_id, period_bucket FROM scoring.fn_lo_tinh_den_han(:limit)", limit);
  }

  @Override
  public List<Ref> waitingJobs(int limit) {
    return refs(
        "SELECT id, tenant_id, period_bucket FROM scoring.fn_tac_vu_tinh_cho(:limit)", limit);
  }

  private List<Ref> refs(String sql, int limit) {
    return jdbc.sql(sql)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Ref(
                    rs.getObject("tenant_id", UUID.class),
                    rs.getInt("period_bucket"),
                    rs.getObject("id", UUID.class)))
        .list();
  }

  @Override
  public long pending() {
    Long n = jdbc.sql("SELECT scoring.fn_dem_lo_tinh_cho()").query(Long.class).single();
    return n == null ? 0L : n;
  }

  @Override
  public Optional<Job> startJob(Ref job) {
    return jdbc.sql(START_SQL)
        .param("bucket", job.periodBucket())
        .param("tenant", job.tenantId())
        .param("id", job.id())
        .query(
            (rs, n) ->
                new Job(
                    job,
                    rs.getObject("period_id", UUID.class),
                    ScoreJobKind.valueOf(rs.getString("job_kind")),
                    rs.getString("scope"),
                    rs.getObject("policy_pack_version_id", UUID.class),
                    rs.getObject("created_by", UUID.class),
                    rs.getInt("total_items")))
        .optional();
  }

  @Override
  public void enqueue(Ref job, int seq, String cursor) {
    jdbc.sql(
            "INSERT INTO scoring.score_job_chunk (id, tenant_id, period_bucket, score_job_id, seq,"
                + " page_cursor) VALUES (:id, :tenant, :bucket, :job, :seq, :cursor)"
                + " ON CONFLICT (period_bucket, tenant_id, score_job_id, seq) DO NOTHING")
        .param("id", Ids.newUuidV7())
        .param("tenant", job.tenantId())
        .param("bucket", job.periodBucket())
        .param("job", job.id())
        .param("seq", seq)
        .param("cursor", cursor)
        .update();
  }

  @Override
  public Optional<Claimed> claim(Ref chunk, Duration lease) {
    return jdbc.sql(CLAIM_SQL)
        .param("lease", (double) lease.toSeconds())
        .param("bucket", chunk.periodBucket())
        .param("tenant", chunk.tenantId())
        .param("id", chunk.id())
        .query(
            (rs, n) ->
                new Claimed(
                    chunk,
                    rs.getInt("seq"),
                    rs.getString("page_cursor"),
                    rs.getInt("attempts"),
                    new Job(
                        new Ref(
                            chunk.tenantId(),
                            chunk.periodBucket(),
                            rs.getObject("job_id", UUID.class)),
                        rs.getObject("period_id", UUID.class),
                        ScoreJobKind.valueOf(rs.getString("job_kind")),
                        rs.getString("scope"),
                        rs.getObject("policy_pack_version_id", UUID.class),
                        rs.getObject("created_by", UUID.class),
                        rs.getInt("total_items"))))
        .optional();
  }

  @Override
  public void lockJob(Ref job) {
    jdbc.sql(
            "SELECT id FROM scoring.score_job WHERE period_bucket = :bucket AND tenant_id = :tenant"
                + " AND id = :id FOR UPDATE")
        .param("bucket", job.periodBucket())
        .param("tenant", job.tenantId())
        .param("id", job.id())
        .query(UUID.class)
        .optional()
        .orElseThrow(() -> new IllegalStateException("Không tìm thấy tác vụ tính " + job.id()));
  }

  @Override
  public void complete(Ref chunk, int items) {
    set(chunk, "status = 'XONG', items = :items, last_error = NULL", "items", items);
  }

  @Override
  public void retry(Ref chunk, Duration delay, String error) {
    jdbc.sql(
            "UPDATE scoring.score_job_chunk SET status = 'CHO', last_error = :error,"
                + " lease_until = now() + make_interval(secs => :delay), updated_at = now()"
                + " WHERE period_bucket = :bucket AND tenant_id = :tenant AND id = :id")
        .param("error", trim(error))
        .param("delay", (double) delay.toSeconds())
        .param("bucket", chunk.periodBucket())
        .param("tenant", chunk.tenantId())
        .param("id", chunk.id())
        .update();
  }

  @Override
  public void fail(Ref chunk, String error) {
    set(chunk, "status = 'LOI', last_error = :error", "error", trim(error));
  }

  private void set(Ref chunk, String assignments, String name, Object value) {
    jdbc.sql(
            "UPDATE scoring.score_job_chunk SET "
                + assignments
                + ", updated_at = now() WHERE period_bucket = :bucket AND tenant_id = :tenant"
                + " AND id = :id")
        .param(name, value)
        .param("bucket", chunk.periodBucket())
        .param("tenant", chunk.tenantId())
        .param("id", chunk.id())
        .update();
  }

  @Override
  public void errors(Ref job, int seq, int attempts, List<ScoreJobError> errors) {
    for (ScoreJobError e : errors) {
      jdbc.sql(
              "INSERT INTO scoring.score_job_error (id, tenant_id, period_bucket, score_job_id,"
                  + " chunk_seq, profile_id, person_id, flow, reason_code, detail, attempts)"
                  + " VALUES (:id, :tenant, :bucket, :job, :seq, :profile, :person, :flow, :reason,"
                  + " :detail, :attempts)")
          .param("id", Ids.newUuidV7())
          .param("tenant", job.tenantId())
          .param("bucket", job.periodBucket())
          .param("job", job.id())
          .param("seq", seq)
          .param("profile", e.profileId())
          .param("person", e.personId())
          .param("flow", e.flow())
          .param("reason", e.reasonCode())
          .param("detail", e.detail())
          .param("attempts", attempts)
          .update();
    }
  }

  private static String trim(String error) {
    if (error == null) {
      return null;
    }
    return error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) : error;
  }
}
