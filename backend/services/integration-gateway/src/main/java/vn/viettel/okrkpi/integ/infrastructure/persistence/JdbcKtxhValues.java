package vn.viettel.okrkpi.integ.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.integ.application.port.KtxhValues;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@code integ.ktxh_value}: một dòng cho mỗi chỉ tiêu, kỳ, địa bàn của một kết nối LGSP. */
public class JdbcKtxhValues implements KtxhValues {

  private final JdbcClient jdbc;

  public JdbcKtxhValues(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean upsert(TenantId tenant, UUID id, UUID connectorId, Value v, Instant at) {
    return jdbc.sql(
                "INSERT INTO integ.ktxh_value AS k (id, tenant_id, connector_id, indicator_code,"
                    + " period_code, area_code, value, unit, da_cong_bo, published_at,"
                    + " source_updated_at, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (tenant_id, connector_id, indicator_code, period_code, area_code)"
                    + " DO UPDATE SET value = EXCLUDED.value, unit = EXCLUDED.unit,"
                    + " da_cong_bo = EXCLUDED.da_cong_bo, published_at = EXCLUDED.published_at,"
                    + " source_updated_at = EXCLUDED.source_updated_at,"
                    + " received_at = EXCLUDED.received_at"
                    + " WHERE k.source_updated_at IS NULL OR EXCLUDED.source_updated_at IS NULL"
                    + " OR EXCLUDED.source_updated_at >= k.source_updated_at")
            .params(
                id,
                tenant.value(),
                connectorId,
                v.indicatorCode(),
                v.periodCode(),
                v.areaCode(),
                v.value(),
                v.unit(),
                v.publishedAt() != null,
                v.publishedAt() == null ? null : Timestamp.from(v.publishedAt()),
                v.sourceUpdatedAt() == null ? null : Timestamp.from(v.sourceUpdatedAt()),
                Timestamp.from(at))
            .update()
        == 1;
  }

  @Override
  public Optional<Value> latestPublished(TenantId tenant, UUID connectorId, String indicatorCode) {
    return jdbc.sql(
            "SELECT indicator_code, period_code, area_code, value, unit, published_at,"
                + " source_updated_at FROM integ.ktxh_value WHERE tenant_id = ? AND connector_id = ?"
                + " AND indicator_code = ? AND da_cong_bo"
                + " ORDER BY period_code DESC, published_at DESC LIMIT 1")
        .params(tenant.value(), connectorId, indicatorCode)
        .query(
            (rs, i) -> {
              Timestamp updated = rs.getTimestamp(7);
              return new Value(
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getBigDecimal(4),
                  rs.getString(5),
                  rs.getTimestamp(6).toInstant(),
                  updated == null ? null : updated.toInstant());
            })
        .optional();
  }
}
