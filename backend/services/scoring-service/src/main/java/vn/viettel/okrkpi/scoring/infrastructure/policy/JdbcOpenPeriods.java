package vn.viettel.okrkpi.scoring.infrastructure.policy;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.scoring.application.port.OpenPeriods;
import vn.viettel.okrkpi.scoring.domain.OpenPeriod;

/**
 * Kỳ, luồng đã có kết quả tính mà chưa có tác vụ {@code CHOT_KY} hoàn tất. Chỉ chạy khi nhận EVT-13
 * (hiếm), trong giao dịch inbox đã đặt ngữ cảnh RLS của tenant.
 */
public class JdbcOpenPeriods implements OpenPeriods {

  static final String SQL =
      """
      SELECT DISTINCT r.period_id, r.period_bucket, r.flow
        FROM scoring.score_result r
       WHERE r.tenant_id = :tenant
         AND NOT EXISTS (SELECT 1 FROM scoring.score_job j
                          WHERE j.tenant_id = r.tenant_id AND j.period_bucket = r.period_bucket
                            AND j.period_id = r.period_id AND j.job_kind = 'CHOT_KY'
                            AND j.status = 'XONG')
       ORDER BY r.period_bucket, r.period_id, r.flow
      """;

  private final JdbcClient jdbc;

  public JdbcOpenPeriods(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<OpenPeriod> of(UUID tenantId) {
    return jdbc.sql(SQL)
        .param("tenant", tenantId)
        .query(
            (rs, i) ->
                new OpenPeriod(
                    rs.getObject("period_id", UUID.class),
                    rs.getInt("period_bucket"),
                    rs.getString("flow")))
        .list();
  }
}
