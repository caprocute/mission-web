package vn.viettel.okrkpi.scoring.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
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
          + " r.result_doc->>'policy_version' AS policy_version, i.input_doc::text AS input_doc"
          + " FROM scoring.score_result r JOIN scoring.score_input_v i"
          + " ON i.period_bucket = r.period_bucket AND i.tenant_id = r.tenant_id"
          + " AND i.input_hash = r.input_hash WHERE r.status = 'OK' AND ";

  private static final String ORDER = " ORDER BY r.computed_at DESC, r.id DESC LIMIT 1";

  private final JdbcClient jdbc;

  public JdbcStoredScores(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Stored> latest(UUID profileId) {
    return jdbc.sql(SELECT + "r.profile_id = :profile" + ORDER)
        .param("profile", profileId)
        .query(JdbcStoredScores::row)
        .optional();
  }

  @Override
  public Optional<Stored> latestWithTask(UUID taskId) {
    return jdbc.sql(SELECT + "i.input_doc->'nhiem_vu' @> CAST(:probe AS jsonb)" + ORDER)
        .param("probe", "[{\"task_id\":\"" + taskId + "\"}]")
        .query(JdbcStoredScores::row)
        .optional();
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
        rs.getString("input_doc"));
  }
}
