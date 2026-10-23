package vn.viettel.okrkpi.scoring.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.scoring.application.port.ScoreTraceStore;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ScoreTrace;
import vn.viettel.okrkpi.scoring.domain.TraceStep;

/**
 * Đọc vết tính bằng JDBC; RLS của {@code scoring} giới hạn theo tenant của giao dịch. Kết quả mới
 * nhất chọn theo {@code (computed_at, id)} trên chỉ mục {@code ix_score_result__profile}, rồi đọc
 * bước theo khoá phân vùng của kết quả.
 */
public class JdbcScoreTraceStore implements ScoreTraceStore {

  private static final String LATEST_SQL =
      "SELECT id, tenant_id, period_bucket, person_id, flow, engine_version,"
          + " result_doc->>'policy_version' AS policy_version FROM scoring.score_result"
          + " WHERE profile_id = :profile AND status = 'OK'"
          + " AND (CAST(:flow AS varchar) IS NULL OR flow = CAST(:flow AS varchar))"
          + " ORDER BY computed_at DESC, id DESC LIMIT 1";

  private static final String STEPS_SQL =
      "SELECT step_no, rule_code, legal_ref, inputs::text AS inputs, output::text AS output,"
          + " explanation FROM scoring.score_trace WHERE period_bucket = :bucket"
          + " AND score_result_id = :result ORDER BY step_no";

  private final JdbcClient jdbc;

  public JdbcScoreTraceStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private record Head(
      UUID id,
      UUID tenantId,
      int bucket,
      UUID personId,
      String flow,
      String engineVersion,
      String policyVersion) {}

  @Override
  public Optional<ScoreTrace> latest(UUID profileId, String flow) {
    Optional<Head> head =
        jdbc.sql(LATEST_SQL)
            .param("profile", profileId)
            .param("flow", flow)
            .query(
                (rs, n) ->
                    new Head(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getInt("period_bucket"),
                        rs.getObject("person_id", UUID.class),
                        rs.getString("flow"),
                        rs.getString("engine_version"),
                        rs.getString("policy_version")))
            .optional();
    return head.map(
        h -> {
          List<TraceStep> steps =
              jdbc.sql(STEPS_SQL)
                  .param("bucket", h.bucket())
                  .param("result", h.id())
                  .query(
                      (rs, n) ->
                          new TraceStep(
                              rs.getInt("step_no"),
                              rs.getString("rule_code"),
                              rs.getString("legal_ref"),
                              CanonicalDoc.parse(rs.getString("inputs")),
                              CanonicalDoc.parse(rs.getString("output")),
                              rs.getString("explanation")))
                  .list();
          return new ScoreTrace(
              h.id(),
              h.tenantId(),
              profileId,
              h.personId(),
              h.flow(),
              h.policyVersion(),
              h.engineVersion(),
              steps);
        });
  }
}
