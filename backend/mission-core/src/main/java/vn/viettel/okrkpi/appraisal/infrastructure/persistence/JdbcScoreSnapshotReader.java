package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.score.port.ScoreSnapshotReader;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Đọc {@code appraisal.score_snapshot} trên {@code appraisalDataSource} (CORE-04-09). */
public class JdbcScoreSnapshotReader implements ScoreSnapshotReader {

  private static final String COLUMNS =
      "id, profile_id, flow, snapshot_kind, score_result_id, policy_pack_version_id,"
          + " policy_version, policy_content_hash, input_hash, snapshot_hash, total_score,"
          + " regular_score, key_task_score, general_criteria_score, kpi_score, exceed_ratio,"
          + " objective_ratio, general_criteria_level, auto_rating_code, rule_checks::text,"
          + " downgrade_reasons::text, group_scores_json::text, detail_json::text, computed_at,"
          + " superseded_by_id";

  private final JdbcClient jdbc;

  public JdbcScoreSnapshotReader(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Snapshot> current(TenantId tenant, int periodBucket, UUID profileId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM appraisal.score_snapshot WHERE period_bucket = ? AND tenant_id = ?"
                + " AND profile_id = ? AND superseded_by_id IS NULL"
                + " ORDER BY computed_at DESC, id DESC LIMIT 1")
        .params(periodBucket, tenant.value(), profileId)
        .query((rs, i) -> snapshot(rs))
        .optional();
  }

  @Override
  public Optional<UUID> sibling(
      TenantId tenant, int periodBucket, UUID periodId, UUID personId, String flow) {
    return jdbc.sql(
            "SELECT id FROM appraisal.appraisal_profile WHERE period_bucket = ? AND tenant_id = ?"
                + " AND period_id = ? AND person_id = ? AND flow = ? ORDER BY id LIMIT 1")
        .params(periodBucket, tenant.value(), periodId, personId, flow)
        .query(UUID.class)
        .optional();
  }

  static Snapshot snapshot(ResultSet rs) throws SQLException {
    Timestamp computed = rs.getTimestamp(24);
    return new Snapshot(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getString(3),
        rs.getString(4),
        rs.getObject(5, UUID.class),
        rs.getObject(6, UUID.class),
        rs.getString(7),
        rs.getBytes(8),
        rs.getBytes(9),
        rs.getBytes(10),
        rs.getBigDecimal(11),
        rs.getBigDecimal(12),
        rs.getBigDecimal(13),
        rs.getBigDecimal(14),
        rs.getBigDecimal(15),
        rs.getBigDecimal(16),
        rs.getBigDecimal(17),
        rs.getString(18),
        rs.getString(19),
        rs.getString(20),
        rs.getString(21),
        rs.getString(22),
        rs.getString(23),
        computed == null ? null : computed.toInstant(),
        rs.getObject(25, UUID.class));
  }
}
