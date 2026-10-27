package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.appraisal.application.unitresult.port.UnitResultRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hồ sơ đánh giá kỳ quý của đơn vị và cây cấp dưới, nối ảnh chụp điểm hiện hành (AP-09). Cây đơn vị
 * lấy theo luồng của từng hồ sơ ({@code DANG} hoặc {@code CHUYEN_MON}); kỳ năm ({@code
 * period_bucket = năm × 10}) bị loại vì có thao tác riêng {@code listAnnualAppraisalRatings}.
 */
public class JdbcUnitResultRepository implements UnitResultRepository {

  static final String SQL =
      """
      SELECT p.id, p.person_id, pv.full_name, p.org_unit_id, u.name, p.flow, p.status,
             s.id, s.total_score, s.auto_rating_code, p.final_rating_code
        FROM appraisal.appraisal_profile p
        LEFT JOIN appraisal.score_snapshot s
          ON s.period_bucket = p.period_bucket AND s.tenant_id = p.tenant_id
         AND s.id = p.current_snapshot_id
        LEFT JOIN org_replica.person_v pv ON pv.tenant_id = p.tenant_id AND pv.id = p.person_id
        LEFT JOIN LATERAL (
              SELECT ou.name FROM org_replica.org_unit_v ou
               WHERE ou.tenant_id = p.tenant_id AND ou.id = p.org_unit_id
               ORDER BY ou.valid_from DESC LIMIT 1) u ON true
       WHERE p.tenant_id = ? AND p.period_id = ? AND p.period_bucket % 10 <> 0
         AND p.org_unit_id IN (
               SELECT uc.descendant_id FROM org_replica.unit_closure_v uc
                WHERE uc.tenant_id = p.tenant_id
                  AND uc.tree = CASE WHEN p.flow = 'DANG' THEN 'DANG' ELSE 'CHUYEN_MON' END
                  AND uc.ancestor_id = ?)
      """;

  private final JdbcClient jdbc;

  public JdbcUnitResultRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<UnitResultRow> listUnitResults(TenantId tenant, Filter filter, int limit) {
    StringBuilder sql = new StringBuilder(SQL);
    List<Object> params = new ArrayList<>();
    params.add(tenant.value());
    params.add(filter.periodId());
    params.add(filter.unitId());
    if (filter.flow() != null) {
      sql.append(" AND p.flow = ?");
      params.add(filter.flow());
    }
    if (filter.after() != null) {
      sql.append(" AND p.id > ?");
      params.add(filter.after());
    }
    sql.append(" AND (").append(filter.scope().sql()).append(')');
    params.addAll(filter.scope().params());
    sql.append(" ORDER BY p.id LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query((rs, i) -> row(rs)).list();
  }

  static UnitResultRow row(ResultSet rs) throws SQLException {
    return new UnitResultRow(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getString(3),
        rs.getObject(4, UUID.class),
        rs.getString(5),
        rs.getString(6),
        rs.getString(7),
        rs.getObject(8, UUID.class),
        rs.getBigDecimal(9),
        rs.getString(10),
        rs.getString(11));
  }
}
