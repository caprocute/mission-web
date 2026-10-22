package vn.viettel.okrkpi.okr.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinReminderRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * CORE-01-11: đọc KR cần nhắc check-in trên {@code okrDataSource}; khoá giữ chỗ dùng {@code
 * okr.idempotency_key} với người gọi là hệ thống (UUID toàn 0), giữ 48 đến 72 giờ nên đủ phủ một
 * tuần nhắc từ thứ Năm tới Chủ nhật.
 */
public class JdbcOkrCheckinReminderRepository implements OkrCheckinReminderRepository {

  static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  private static final String SELECT =
      "SELECT k.id, k.code, k.checkin_frequency, k.objective_id, o.owner_unit_id,"
          + " coalesce(o.owner_person_id, o.created_by) AS owner,"
          + " coalesce(o.approved_at, k.created_at) AS active_since, k.row_version,"
          + " (SELECT max(c.checkin_at) FROM okr.kr_checkin c WHERE c.tenant_id = k.tenant_id"
          + "   AND c.period_bucket = k.period_bucket AND c.key_result_id = k.id"
          + "   AND c.status = 'DA_GUI') AS last_submitted_at,"
          + " (SELECT d.delegate_person_id FROM okr.kr_checkin_delegate d"
          + "   WHERE d.tenant_id = k.tenant_id AND d.key_result_id = k.id"
          + "   AND d.revoked_at IS NULL AND (d.valid_until IS NULL OR d.valid_until >= :today)"
          + "   LIMIT 1) AS delegate"
          + " FROM okr.key_result k JOIN okr.objective o ON o.tenant_id = k.tenant_id"
          + "  AND o.period_bucket = k.period_bucket AND o.id = k.objective_id"
          + " WHERE k.tenant_id = :t AND k.period_id = :p AND o.status = 'DA_DUYET'"
          + "  AND k.status <> 'DONG'";

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcOkrCheckinReminderRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<DueKeyResult> dueKeyResults(
      TenantId tenant, UUID periodId, LocalDate today, UUID afterId, int limit) {
    MapSqlParameterSource p =
        new MapSqlParameterSource("t", tenant.value())
            .addValue("p", periodId)
            .addValue("today", today)
            .addValue("after", afterId)
            .addValue("n", limit);
    return jdbc.query(
        SELECT + (afterId == null ? "" : " AND k.id > :after") + " ORDER BY k.id LIMIT :n",
        p,
        (rs, i) -> {
          Timestamp last = rs.getTimestamp("last_submitted_at");
          Timestamp since = rs.getTimestamp("active_since");
          return new DueKeyResult(
              rs.getObject("id", UUID.class),
              rs.getString("code"),
              rs.getString("checkin_frequency"),
              rs.getObject("objective_id", UUID.class),
              rs.getObject("owner_unit_id", UUID.class),
              rs.getObject("owner", UUID.class),
              rs.getObject("delegate", UUID.class),
              since == null ? null : since.toInstant(),
              last == null ? null : last.toInstant(),
              rs.getLong("row_version"));
        });
  }

  @Override
  public boolean claimNotice(TenantId tenant, String key) {
    // Khoá chính có created_day nên phải tự kiểm khoá của những ngày trước trong cùng tuần.
    return jdbc.update(
            "INSERT INTO okr.idempotency_key (tenant_id, actor_id, idem_key, request_hash,"
                + " status, response_code) SELECT :t, :a, :k, sha256(convert_to(:k, 'UTF8')),"
                + " 'XONG', 200 WHERE NOT EXISTS (SELECT 1 FROM okr.idempotency_key"
                + " WHERE tenant_id = :t AND actor_id = :a AND idem_key = :k)"
                + " ON CONFLICT DO NOTHING",
            new MapSqlParameterSource("t", tenant.value())
                .addValue("a", SYSTEM_ACTOR)
                .addValue("k", key))
        == 1;
  }
}
