package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.sql.Array;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.transfer.port.RatingHistory;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Lịch sử xếp loại đã chốt của một cán bộ cho gói chuyển liên cell (DV-13-10). */
public class JdbcRatingHistory implements RatingHistory {

  private final JdbcClient jdbc;

  public JdbcRatingHistory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<PeriodRating> periodRatings(TenantId tenant, UUID personId) {
    return jdbc.sql(
            "SELECT period_id, flow, final_rating_code, decided_at FROM appraisal.appraisal_profile"
                + " WHERE tenant_id = ? AND person_id = ? AND final_rating_code IS NOT NULL"
                + " AND status IN ('DA_KY_SO', 'DA_KHOA') ORDER BY decided_at, period_id")
        .params(tenant.value(), personId)
        .query(
            (rs, i) -> {
              Timestamp t = rs.getTimestamp("decided_at");
              return new PeriodRating(
                  rs.getObject("period_id", UUID.class),
                  rs.getString("flow"),
                  rs.getString("final_rating_code"),
                  t == null ? null : t.toInstant());
            })
        .list();
  }

  @Override
  public List<AnnualRating> annualRatings(TenantId tenant, UUID personId) {
    return jdbc.sql(
            "SELECT period_id, flow, quarter_ratings, annual_score, rating_code"
                + " FROM appraisal.annual_rating WHERE tenant_id = ? AND person_id = ?"
                + " AND rating_code IS NOT NULL ORDER BY period_bucket, flow")
        .params(tenant.value(), personId)
        .query(
            (rs, i) -> {
              Array q = rs.getArray("quarter_ratings");
              return new AnnualRating(
                  rs.getObject("period_id", UUID.class),
                  rs.getString("flow"),
                  q == null
                      ? List.of()
                      : Arrays.stream((Object[]) q.getArray()).map(String::valueOf).toList(),
                  rs.getBigDecimal("annual_score"),
                  rs.getString("rating_code"));
            })
        .list();
  }
}
