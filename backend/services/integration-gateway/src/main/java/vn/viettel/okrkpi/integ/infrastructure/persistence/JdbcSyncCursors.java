package vn.viettel.okrkpi.integ.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.integ.application.port.SyncCursors;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@code integ.sync_cursor}: ghi đè con trỏ theo khoá (tenant, kết nối, luồng). */
public class JdbcSyncCursors implements SyncCursors {

  private final JdbcClient jdbc;

  public JdbcSyncCursors(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<String> find(TenantId tenant, UUID connectorId, String stream) {
    return jdbc.sql(
            "SELECT cursor_value FROM integ.sync_cursor WHERE tenant_id = ? AND connector_id = ?"
                + " AND stream = ?")
        .params(tenant.value(), connectorId, stream)
        .query(String.class)
        .optional();
  }

  @Override
  public void save(
      TenantId tenant,
      UUID connectorId,
      String stream,
      String value,
      Instant succeededAt,
      Integer lagSeconds) {
    jdbc.sql(
            "INSERT INTO integ.sync_cursor (tenant_id, connector_id, stream, cursor_value,"
                + " last_success_at, lag_seconds, updated_at) VALUES (?, ?, ?, ?, ?, ?, now())"
                + " ON CONFLICT (tenant_id, connector_id, stream) DO UPDATE SET"
                + " cursor_value = EXCLUDED.cursor_value, last_success_at = EXCLUDED.last_success_at,"
                + " lag_seconds = EXCLUDED.lag_seconds, updated_at = now()")
        .params(tenant.value(), connectorId, stream, value, Timestamp.from(succeededAt), lagSeconds)
        .update();
  }
}
