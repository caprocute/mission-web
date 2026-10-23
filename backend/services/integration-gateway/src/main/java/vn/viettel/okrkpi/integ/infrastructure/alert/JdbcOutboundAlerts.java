package vn.viettel.okrkpi.integ.infrastructure.alert;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.integ.application.alert.OutboundAlerts;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Thông điệp {@code LOI} qua hàm {@code fn_thu_ra_loi_chua_bao}; dấu cảnh báo {@code
 * outbound_alert}.
 */
public class JdbcOutboundAlerts implements OutboundAlerts {

  private final JdbcClient jdbc;

  public JdbcOutboundAlerts(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Failed> failedUnalerted(Collection<String> connectorCodes, int limit) {
    return jdbc.sql(
            "SELECT tenant_id, id, connector_code FROM integ.fn_thu_ra_loi_chua_bao(?::varchar[], ?)")
        .params(connectorCodes.toArray(String[]::new), limit)
        .query(
            (rs, i) ->
                new Failed(
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("id", UUID.class),
                    rs.getString("connector_code")))
        .list();
  }

  @Override
  public boolean markAlerted(TenantId tenant, UUID id, UUID messageId) {
    return jdbc.sql(
                "INSERT INTO integ.outbound_alert (id, tenant_id, outbound_message_id)"
                    + " VALUES (?, ?, ?) ON CONFLICT (tenant_id, outbound_message_id) DO NOTHING")
            .params(id, tenant.value(), messageId)
            .update()
        == 1;
  }
}
