package vn.viettel.okrkpi.scoring.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.scoring.application.port.StoredScores;

/**
 * Đọc kết quả gốc và {@code input_doc} bằng JDBC dưới RLS của {@code scoring} (DV-07-08). Ghép
 * {@code score_input_v} theo khoá {@code (period_bucket, tenant_id, input_hash)} của ràng buộc duy
 * nhất. Tìm theo nhiệm vụ dùng phép chứa {@code @>} trên {@code input_doc->'nhiem_vu'}, chưa có chỉ
 * mục GIN (wiki 14).
 */
public class JdbcStoredScores implements StoredScores {

  private static final String SELECT =
      "SELECT r.id, r.tenant_id, r.period_id, r.profile_id, r.person_id, r.flow,"
          + " r.policy_pack_version_id, encode(r.policy_content_hash, 'hex') AS policy_hash,"
          + " r.result_doc->>'policy_version' AS policy_version, i.input_doc::text AS input_doc,"
          + " r.period_bucket, r.score_job_id"
          + " FROM scoring.score_result r JOIN scoring.score_input_v i"
          + " ON i.period_bucket = r.period_bucket AND i.tenant_id = r.tenant_id"
          + " AND i.input_hash = r.input_hash WHERE r.status = 'OK' AND ";

  /** Kết quả của tác vụ tính lại đối chiếu không phải ảnh chụp (DV-07-06). */
  static final String NOT_RECHECK =
      " AND NOT EXISTS (SELECT 1 FROM scoring.score_job k WHERE k.period_bucket = r.period_bucket"
          + " AND k.tenant_id = r.tenant_id AND k.id = r.score_job_id"
          + " AND k.job_kind = 'KIEM_TRA_TAI_LAP')";

  private static final String OPEN_PERIOD =
      " AND NOT EXISTS (SELECT 1 FROM scoring.score_job c WHERE c.tenant_id = r.tenant_id"
          + " AND c.period_bucket = r.period_bucket AND c.period_id = r.period_id"
          + " AND c.job_kind = 'CHOT_KY' AND c.status = 'XONG')";

  private static final String WITH_TASK_SQL =
      "SELECT DISTINCT ON (r.profile_id, r.flow) r.period_id, r.period_bucket, r.profile_id,"
          + " r.flow, i.input_doc->'nhiem_vu' @> CAST(:probe AS jsonb) AS hit"
          + " FROM scoring.score_result r JOIN scoring.score_input_v i"
          + " ON i.period_bucket = r.period_bucket AND i.tenant_id = r.tenant_id"
          + " AND i.input_hash = r.input_hash WHERE r.status = 'OK'"
          + " AND r.profile_id IN (SELECT x.profile_id FROM scoring.score_result x"
          + " JOIN scoring.score_input_v y ON y.period_bucket = x.period_bucket"
          + " AND y.tenant_id = x.tenant_id AND y.input_hash = x.input_hash"
          + " WHERE y.input_doc->'nhiem_vu' @> CAST(:probe AS jsonb))"
          + NOT_RECHECK
          + OPEN_PERIOD
          + " ORDER BY r.profile_id, r.flow, r.computed_at DESC, r.id DESC";

  private static final String ORIGINALS_SQL =
      "SELECT DISTINCT ON (r.flow) r.id, r.tenant_id, r.period_id, r.profile_id, r.person_id,"
          + " r.flow, r.policy_pack_version_id, encode(r.policy_content_hash, 'hex') AS policy_hash,"
          + " r.result_doc->>'policy_version' AS policy_version, NULL::text AS input_doc,"
          + " r.period_bucket, r.score_job_id"
          + " FROM scoring.score_result r JOIN scoring.score_job j ON j.period_bucket = r.period_bucket"
          + " AND j.tenant_id = r.tenant_id AND j.id = r.score_job_id"
          + " WHERE r.period_bucket = :bucket AND r.period_id = :period AND r.status = 'OK'"
          + " AND j.job_kind <> 'KIEM_TRA_TAI_LAP'"
          + " ORDER BY r.flow, (j.job_kind = 'CHOT_KY') DESC, r.computed_at DESC, r.id DESC";

  private static final String ORDER = " ORDER BY r.computed_at DESC, r.id DESC LIMIT 1";

  private final JdbcClient jdbc;

  public JdbcStoredScores(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Stored> latest(UUID profileId) {
    return jdbc.sql(SELECT + "r.profile_id = :profile" + NOT_RECHECK + ORDER)
        .param("profile", profileId)
        .query(JdbcStoredScores::row)
        .optional();
  }

  @Override
  public Optional<Stored> latestWithTask(UUID taskId) {
    return jdbc.sql(
            SELECT + "i.input_doc->'nhiem_vu' @> CAST(:probe AS jsonb)" + NOT_RECHECK + ORDER)
        .param("probe", probe(taskId))
        .query(JdbcStoredScores::row)
        .optional();
  }

  @Override
  public Optional<Stored> byId(int periodBucket, UUID resultId) {
    return jdbc.sql(SELECT + "r.period_bucket = :bucket AND r.id = :id")
        .param("bucket", periodBucket)
        .param("id", resultId)
        .query(JdbcStoredScores::row)
        .optional();
  }

  @Override
  public List<Affected> withTask(UUID taskId) {
    return jdbc
        .sql(WITH_TASK_SQL)
        .param("probe", probe(taskId))
        .query(
            (rs, n) ->
                rs.getBoolean("hit")
                    ? new Affected(
                        rs.getObject("period_id", UUID.class),
                        rs.getInt("period_bucket"),
                        rs.getObject("profile_id", UUID.class),
                        rs.getString("flow"))
                    : null)
        .list()
        .stream()
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  @Override
  public Optional<Integer> bucketOf(UUID periodId) {
    return jdbc.sql(
            "SELECT period_bucket FROM scoring.score_result WHERE period_id = :period LIMIT 1")
        .param("period", periodId)
        .query(Integer.class)
        .optional();
  }

  @Override
  public List<Stored> originals(UUID periodId, int periodBucket) {
    return jdbc.sql(ORIGINALS_SQL)
        .param("bucket", periodBucket)
        .param("period", periodId)
        .query(JdbcStoredScores::row)
        .list();
  }

  private static String probe(UUID taskId) {
    return "[{\"task_id\":\"" + taskId + "\"}]";
  }

  private static Stored row(ResultSet rs, int n) throws SQLException {
    return new Stored(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getObject("profile_id", UUID.class),
        rs.getObject("person_id", UUID.class),
        rs.getString("flow"),
        rs.getObject("policy_pack_version_id", UUID.class),
        rs.getString("policy_hash"),
        rs.getString("policy_version"),
        rs.getString("input_doc"),
        rs.getInt("period_bucket"),
        rs.getObject("score_job_id", UUID.class));
  }
}
