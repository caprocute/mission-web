package vn.viettel.okrkpi.integ.infrastructure.anchor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.integ.application.anchor.AnchorRecords;
import vn.viettel.okrkpi.integ.application.anchor.AnchorTarget;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@code integ.anchor_window} và {@code integ.anchor_dispatch} của tenant vận hành cell. */
public class JdbcAnchorRecords implements AnchorRecords {

  private final JdbcClient jdbc;

  public JdbcAnchorRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean registerWindow(
      TenantId tenant, UUID id, String cellCode, Instant windowEnd, UUID sourceEventId) {
    return jdbc.sql(
                "INSERT INTO integ.anchor_window (id, tenant_id, cell_code, window_end,"
                    + " source_event_id) VALUES (?, ?, ?, ?, ?)"
                    + " ON CONFLICT (tenant_id, cell_code, window_end) DO NOTHING")
            .params(id, tenant.value(), cellCode, Timestamp.from(windowEnd), sourceEventId)
            .update()
        == 1;
  }

  @Override
  public List<Window> lockDueWindows(TenantId tenant, Instant now, int limit) {
    return jdbc.sql(
            "SELECT id, cell_code, window_end, attempts FROM integ.anchor_window"
                + " WHERE tenant_id = ? AND fanned_out_at IS NULL AND next_attempt_at <= ?"
                + " ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED")
        .params(tenant.value(), Timestamp.from(now), limit)
        .query(
            (rs, i) ->
                new Window(
                    rs.getObject("id", UUID.class),
                    rs.getString("cell_code"),
                    rs.getTimestamp("window_end").toInstant(),
                    rs.getInt("attempts")))
        .list();
  }

  @Override
  public void postponeWindow(TenantId tenant, UUID windowId, int attempts, Instant next) {
    jdbc.sql(
            "UPDATE integ.anchor_window SET attempts = ?, next_attempt_at = ?, updated_at = now()"
                + " WHERE tenant_id = ? AND id = ?")
        .params(attempts, Timestamp.from(next), tenant.value(), windowId)
        .update();
  }

  @Override
  public void windowFannedOut(TenantId tenant, UUID windowId, UUID cellSealId, Instant at) {
    jdbc.sql(
            "UPDATE integ.anchor_window SET cell_seal_id = ?, fanned_out_at = ?,"
                + " updated_at = now() WHERE tenant_id = ? AND id = ?")
        .params(cellSealId, Timestamp.from(at), tenant.value(), windowId)
        .update();
  }

  @Override
  public void insertDispatch(
      TenantId tenant,
      UUID id,
      UUID windowId,
      UUID cellSealId,
      AnchorTarget target,
      UUID connectorId,
      UUID outboundMessageId) {
    jdbc.sql(
            "INSERT INTO integ.anchor_dispatch (id, tenant_id, window_id, cell_seal_id,"
                + " anchor_target, connector_id, outbound_message_id) VALUES (?, ?, ?, ?, ?, ?, ?)")
        .params(
            id, tenant.value(), windowId, cellSealId, target.name(), connectorId, outboundMessageId)
        .update();
  }

  @Override
  public Optional<UUID> dispatchOf(TenantId tenant, UUID messageId) {
    return jdbc.sql(
            "SELECT id FROM integ.anchor_dispatch WHERE tenant_id = ? AND outbound_message_id = ?")
        .params(tenant.value(), messageId)
        .query(UUID.class)
        .optional();
  }

  @Override
  public void recordReceipt(TenantId tenant, UUID dispatchId, byte[] receipt, Instant at) {
    jdbc.sql(
            "UPDATE integ.anchor_dispatch SET receipt = ?, received_at = ?, updated_at = now()"
                + " WHERE tenant_id = ? AND id = ? AND receipt IS NULL")
        .params(receipt, Timestamp.from(at), tenant.value(), dispatchId)
        .update();
  }

  @Override
  public List<PendingReceipt> lockPendingReceipts(TenantId tenant, int limit) {
    return jdbc.sql(
            "SELECT d.id, d.cell_seal_id, d.anchor_target, c.connector_code, d.receipt,"
                + " d.received_at, d.relay_attempts FROM integ.anchor_dispatch d"
                + " JOIN integ.connector c ON c.tenant_id = d.tenant_id AND c.id = d.connector_id"
                + " WHERE d.tenant_id = ? AND d.receipt IS NOT NULL AND d.relayed_at IS NULL"
                + " ORDER BY d.received_at LIMIT ? FOR UPDATE OF d SKIP LOCKED")
        .params(tenant.value(), limit)
        .query(
            (rs, i) ->
                new PendingReceipt(
                    rs.getObject("id", UUID.class),
                    rs.getObject("cell_seal_id", UUID.class),
                    AnchorTarget.valueOf(rs.getString("anchor_target")),
                    rs.getString("connector_code"),
                    rs.getBytes("receipt"),
                    rs.getTimestamp("received_at").toInstant(),
                    rs.getInt("relay_attempts")))
        .list();
  }

  @Override
  public void receiptRelayed(TenantId tenant, UUID dispatchId, Instant at) {
    jdbc.sql(
            "UPDATE integ.anchor_dispatch SET relayed_at = ?, relay_attempts = relay_attempts + 1,"
                + " updated_at = now() WHERE tenant_id = ? AND id = ? AND relayed_at IS NULL")
        .params(Timestamp.from(at), tenant.value(), dispatchId)
        .update();
  }

  @Override
  public void relayFailed(TenantId tenant, UUID dispatchId, int attempts) {
    jdbc.sql(
            "UPDATE integ.anchor_dispatch SET relay_attempts = ?, updated_at = now()"
                + " WHERE tenant_id = ? AND id = ?")
        .params(attempts, tenant.value(), dispatchId)
        .update();
  }

  @Override
  public List<Unchecked> lockUnchecked(TenantId tenant, Instant before, int limit) {
    return jdbc.sql(
            "SELECT w.id, w.cell_code, w.window_end, w.fanned_out_at IS NOT NULL AS fanned_out,"
                + " (SELECT count(*) FROM integ.anchor_dispatch d WHERE d.tenant_id = w.tenant_id"
                + " AND d.window_id = w.id AND d.receipt IS NOT NULL) AS receipts"
                + " FROM integ.anchor_window w WHERE w.tenant_id = ? AND w.checked_at IS NULL"
                + " AND w.window_end <= ? ORDER BY w.window_end LIMIT ? FOR UPDATE OF w SKIP LOCKED")
        .params(tenant.value(), Timestamp.from(before), limit)
        .query(
            (rs, i) ->
                new Unchecked(
                    rs.getObject("id", UUID.class),
                    rs.getString("cell_code"),
                    rs.getTimestamp("window_end").toInstant(),
                    rs.getBoolean("fanned_out"),
                    rs.getInt("receipts")))
        .list();
  }

  @Override
  public void windowChecked(TenantId tenant, UUID windowId, Instant at) {
    jdbc.sql(
            "UPDATE integ.anchor_window SET checked_at = ?, updated_at = now()"
                + " WHERE tenant_id = ? AND id = ?")
        .params(Timestamp.from(at), tenant.value(), windowId)
        .update();
  }
}
