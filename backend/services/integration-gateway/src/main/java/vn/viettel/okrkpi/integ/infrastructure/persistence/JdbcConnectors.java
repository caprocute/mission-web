package vn.viettel.okrkpi.integ.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@code integ.connector} dưới RLS; danh sách kết nối đang bật qua hàm quét liên tenant. */
public class JdbcConnectors implements Connectors {

  private static final String COLUMNS =
      "id, tenant_id, connector_code, protocol, endpoint, secret_path, direction, schedule_cron,"
          + " enabled";

  private final JdbcClient jdbc;

  public JdbcConnectors(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Connector> findByCode(TenantId tenant, String code) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM integ.connector WHERE tenant_id = ? AND connector_code = ?")
        .params(tenant.value(), code)
        .query(JdbcConnectors::map)
        .optional();
  }

  @Override
  public Optional<Connector> findById(TenantId tenant, UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM integ.connector WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query(JdbcConnectors::map)
        .optional();
  }

  @Override
  public List<Active> enabled() {
    return jdbc.sql("SELECT tenant_id, id, connector_code FROM integ.fn_ket_noi_dang_bat()")
        .query(
            (rs, i) ->
                new Active(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
        .list();
  }

  private static Connector map(ResultSet rs, int row) throws SQLException {
    return new Connector(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("connector_code"),
        Protocol.valueOf(rs.getString("protocol")),
        rs.getString("endpoint"),
        rs.getString("secret_path"),
        Direction.valueOf(rs.getString("direction")),
        rs.getString("schedule_cron"),
        rs.getBoolean("enabled"));
  }
}
