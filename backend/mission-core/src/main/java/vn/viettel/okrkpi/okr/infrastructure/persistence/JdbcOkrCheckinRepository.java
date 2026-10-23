package vn.viettel.okrkpi.okr.infrastructure.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository;
import vn.viettel.okrkpi.okr.domain.ProgressCalculator.Weighted;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hiện thực JDBC của {@link OkrCheckinRepository} trên {@code okrDataSource} (CORE-01-08). Mọi câu
 * lệnh lọc theo {@code period_bucket} để chỉ chạm một phân vùng. {@code checkin_at} lấy {@code
 * clock_timestamp()} để thứ tự nháp, gửi trong cùng giao dịch vẫn đúng.
 */
public class JdbcOkrCheckinRepository implements OkrCheckinRepository {

  private static final String TARGET_SELECT =
      "SELECT k.id, k.period_id, k.period_bucket, k.code, k.metric_type, k.direction,"
          + " k.baseline_value, k.target_value, k.current_value, k.progress, k.confidence,"
          + " k.risk_flag, k.row_version, o.id AS o_id, o.code AS o_code, o.status AS o_status,"
          + " o.progress AS o_progress, o.owner_unit_id, o.owner_person_id,"
          + " o.created_by AS o_created_by"
          + " FROM okr.key_result k JOIN okr.objective o ON o.period_bucket = k.period_bucket"
          + " AND o.tenant_id = k.tenant_id AND o.id = k.objective_id";

  private static final String CHECKIN_COLUMNS =
      "id, key_result_id, period_id, checkin_at, value, confidence, highlights, blocker_note,"
          + " channel, status, source_value, source_fetched_at, override_reason, verified_by,"
          + " verified_at, created_by, idempotency_key";

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcOkrCheckinRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  private static MapSqlParameterSource p(TenantId tenant, int bucket) {
    return new MapSqlParameterSource("t", tenant.value()).addValue("b", bucket);
  }

  @Override
  public Optional<CheckinTarget> target(TenantId tenant, UUID keyResultId) {
    return jdbc
        .query(
            TARGET_SELECT + " WHERE k.tenant_id = :t AND k.id = :id",
            new MapSqlParameterSource("t", tenant.value()).addValue("id", keyResultId),
            JdbcOkrCheckinRepository::target)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<String> dataSource(TenantId tenant, UUID keyResultId) {
    return jdbc
        .query(
            "SELECT data_source FROM okr.key_result WHERE tenant_id = :t AND id = :id",
            new MapSqlParameterSource("t", tenant.value()).addValue("id", keyResultId),
            (rs, i) -> rs.getString(1))
        .stream()
        .filter(Objects::nonNull)
        .map(String::strip)
        .filter(v -> !v.isEmpty())
        .findFirst();
  }

  @Override
  public Optional<CheckinTarget> lockTarget(
      TenantId tenant, int bucket, UUID objectiveId, UUID keyResultId) {
    MapSqlParameterSource p =
        p(tenant, bucket).addValue("o", objectiveId).addValue("id", keyResultId);
    jdbc.query(
        "SELECT 1 FROM okr.objective WHERE period_bucket = :b AND tenant_id = :t AND id = :o"
            + " FOR UPDATE",
        p,
        (rs, i) -> 1);
    return jdbc
        .query(
            TARGET_SELECT
                + " WHERE k.period_bucket = :b AND k.tenant_id = :t AND k.id = :id"
                + " FOR UPDATE OF k",
            p,
            JdbcOkrCheckinRepository::target)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<CheckinRow> lastSubmitted(TenantId tenant, int bucket, UUID keyResultId) {
    return first(
        "SELECT "
            + CHECKIN_COLUMNS
            + " FROM okr.kr_checkin WHERE period_bucket = :b AND tenant_id = :t"
            + " AND key_result_id = :k AND status = 'DA_GUI' ORDER BY checkin_at DESC, id DESC"
            + " LIMIT 1",
        p(tenant, bucket).addValue("k", keyResultId));
  }

  @Override
  public Optional<CheckinRow> draft(TenantId tenant, int bucket, UUID keyResultId, UUID creator) {
    return first(
        "SELECT "
            + CHECKIN_COLUMNS
            + " FROM okr.kr_checkin d WHERE period_bucket = :b AND tenant_id = :t"
            + " AND key_result_id = :k AND status = 'NHAP' AND created_by = :me"
            + " AND checkin_at > coalesce((SELECT max(s.checkin_at) FROM okr.kr_checkin s"
            + "   WHERE s.period_bucket = :b AND s.tenant_id = :t AND s.key_result_id = :k"
            + "   AND s.status = 'DA_GUI' AND s.created_by = :me), '-infinity'::timestamptz)"
            + " ORDER BY checkin_at DESC, id DESC LIMIT 1",
        p(tenant, bucket).addValue("k", keyResultId).addValue("me", creator));
  }

  @Override
  public Optional<CheckinRow> byIdempotencyKey(TenantId tenant, int bucket, String key) {
    return first(
        "SELECT "
            + CHECKIN_COLUMNS
            + " FROM okr.kr_checkin WHERE period_bucket = :b AND tenant_id = :t"
            + " AND idempotency_key = :key",
        p(tenant, bucket).addValue("key", key));
  }

  @Override
  public Optional<CheckinRow> byId(TenantId tenant, int bucket, UUID id) {
    return first(
        "SELECT "
            + CHECKIN_COLUMNS
            + " FROM okr.kr_checkin WHERE period_bucket = :b AND tenant_id = :t AND id = :id",
        p(tenant, bucket).addValue("id", id));
  }

  @Override
  public void insert(TenantId tenant, NewCheckin c, UUID actor) {
    jdbc.update(
        "INSERT INTO okr.kr_checkin (id, period_id, period_bucket, key_result_id, checkin_at,"
            + " value, confidence, highlights, blocker_note, channel, status, idempotency_key,"
            + " source_value, source_fetched_at, override_reason, created_by, updated_by)"
            + " VALUES (:id, :period, :b, :k, clock_timestamp(), :value, :confidence, :highlights,"
            + " :blocker, :channel, :status, :key, :source, :fetched, :override, :actor, :actor)",
        p(tenant, c.periodBucket())
            .addValue("id", c.id())
            .addValue("period", c.periodId())
            .addValue("k", c.keyResultId())
            .addValue("value", c.value())
            .addValue("confidence", c.confidence())
            .addValue("highlights", c.highlights())
            .addValue("blocker", c.blockerNote())
            .addValue("channel", c.channel())
            .addValue("status", c.status())
            .addValue("key", c.idempotencyKey())
            .addValue("source", c.sourceValue())
            .addValue(
                "fetched", c.sourceFetchedAt() == null ? null : Timestamp.from(c.sourceFetchedAt()))
            .addValue("override", c.overrideReason())
            .addValue("actor", actor));
  }

  @Override
  public boolean writeKeyResultProgress(
      TenantId tenant,
      int bucket,
      UUID keyResultId,
      long expectedVersion,
      BigDecimal currentValue,
      String confidence,
      BigDecimal progress,
      String riskFlag,
      UUID actor) {
    return jdbc.update(
            "UPDATE okr.key_result SET current_value = :current, confidence = :confidence,"
                + " progress = :progress, risk_flag = :risk, row_version = row_version + 1, updated_at = now(),"
                + " updated_by = :actor WHERE period_bucket = :b AND tenant_id = :t AND id = :id"
                + " AND row_version = :v",
            p(tenant, bucket)
                .addValue("id", keyResultId)
                .addValue("v", expectedVersion)
                .addValue("current", currentValue)
                .addValue("confidence", confidence)
                .addValue("progress", progress)
                .addValue("risk", riskFlag)
                .addValue("actor", actor))
        == 1;
  }

  @Override
  public List<Weighted> objectiveWeights(TenantId tenant, int bucket, UUID objectiveId) {
    return jdbc.query(
        "SELECT weight, progress FROM okr.key_result WHERE period_bucket = :b AND tenant_id = :t"
            + " AND objective_id = :o ORDER BY code, id",
        p(tenant, bucket).addValue("o", objectiveId),
        (rs, i) -> new Weighted(rs.getBigDecimal("weight"), rs.getBigDecimal("progress")));
  }

  /**
   * Tiến độ mục tiêu là số đo suy ra từ KR nên không tăng {@code row_version}: tránh làm hỏng
   * {@code If-Match} của luồng duyệt, điều chỉnh mục tiêu mỗi lần có check-in (suy luận).
   */
  @Override
  public void writeObjectiveProgress(
      TenantId tenant, int bucket, UUID objectiveId, BigDecimal progress, UUID actor) {
    jdbc.update(
        "UPDATE okr.objective SET progress = :progress, progress_pct = :pct, updated_at = now(),"
            + " updated_by = :actor WHERE period_bucket = :b AND tenant_id = :t AND id = :o",
        p(tenant, bucket)
            .addValue("o", objectiveId)
            .addValue("progress", progress)
            .addValue("pct", progress.movePointRight(2).setScale(3, RoundingMode.HALF_UP))
            .addValue("actor", actor));
  }

  @Override
  public List<CheckinRow> history(
      TenantId tenant,
      int bucket,
      UUID keyResultId,
      UUID includeDraftId,
      Instant afterAt,
      UUID afterId,
      int limit) {
    MapSqlParameterSource p =
        p(tenant, bucket)
            .addValue("k", keyResultId)
            .addValue("draft", includeDraftId)
            .addValue("limit", limit);
    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + CHECKIN_COLUMNS
                + " FROM okr.kr_checkin WHERE period_bucket = :b AND tenant_id = :t"
                + " AND key_result_id = :k AND (status = 'DA_GUI'");
    sql.append(includeDraftId == null ? ")" : " OR id = :draft)");
    if (afterAt != null) {
      sql.append(" AND (checkin_at, id) < (:afterAt, :afterId)");
      p.addValue("afterAt", Timestamp.from(afterAt)).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY checkin_at DESC, id DESC LIMIT :limit");
    return jdbc.query(sql.toString(), p, JdbcOkrCheckinRepository::checkin);
  }

  private Optional<CheckinRow> first(String sql, MapSqlParameterSource p) {
    return jdbc.query(sql, p, JdbcOkrCheckinRepository::checkin).stream().findFirst();
  }

  private static CheckinTarget target(ResultSet rs, int i) throws SQLException {
    return new CheckinTarget(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getString("code"),
        rs.getString("metric_type"),
        rs.getString("direction"),
        rs.getBigDecimal("baseline_value"),
        rs.getBigDecimal("target_value"),
        rs.getBigDecimal("current_value"),
        rs.getBigDecimal("progress"),
        rs.getString("confidence"),
        rs.getString("risk_flag"),
        rs.getLong("row_version"),
        rs.getObject("o_id", UUID.class),
        rs.getString("o_code"),
        rs.getString("o_status"),
        rs.getBigDecimal("o_progress"),
        rs.getObject("owner_unit_id", UUID.class),
        rs.getObject("owner_person_id", UUID.class),
        rs.getObject("o_created_by", UUID.class));
  }

  private static CheckinRow checkin(ResultSet rs, int i) throws SQLException {
    return new CheckinRow(
        rs.getObject("id", UUID.class),
        rs.getObject("key_result_id", UUID.class),
        rs.getObject("period_id", UUID.class),
        instant(rs, "checkin_at"),
        rs.getBigDecimal("value"),
        rs.getString("confidence"),
        rs.getString("highlights"),
        rs.getString("blocker_note"),
        rs.getString("channel"),
        rs.getString("status"),
        rs.getBigDecimal("source_value"),
        instant(rs, "source_fetched_at"),
        rs.getString("override_reason"),
        rs.getObject("verified_by", UUID.class),
        instant(rs, "verified_at"),
        rs.getObject("created_by", UUID.class),
        rs.getString("idempotency_key"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }
}
