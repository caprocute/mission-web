package vn.viettel.okrkpi.integ.infrastructure.persistence;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.integ.application.port.InboxMessages;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher.Sealed;
import vn.viettel.okrkpi.integ.domain.InboxStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@code integ.inbox_message} (phân vùng ngày): UQ trong ngày chặn hai pod ghi trùng cùng lúc, tra
 * trong cửa sổ chống trùng chặn trùng qua ngày.
 */
public class JdbcInboxMessages implements InboxMessages {

  private final JdbcClient jdbc;

  public JdbcInboxMessages(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Stored store(
      TenantId tenant,
      UUID id,
      LocalDate today,
      LocalDate since,
      UUID connectorId,
      String externalMsgId,
      String messageType,
      Sealed raw) {
    Optional<Stored> existing = latest(tenant, connectorId, externalMsgId, since);
    if (existing.isPresent()) {
      return existing.get();
    }
    int inserted =
        jdbc.sql(
                "INSERT INTO integ.inbox_message (received_day, id, tenant_id, connector_id,"
                    + " external_msg_id, message_type, raw_enc, dek_id, status)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MOI') ON CONFLICT DO NOTHING")
            .params(
                Date.valueOf(today),
                id,
                tenant.value(),
                connectorId,
                externalMsgId,
                messageType,
                raw.ciphertext(),
                raw.dekId())
            .update();
    if (inserted == 1) {
      return new Stored(id, today, InboxStatus.MOI, true);
    }
    return latest(tenant, connectorId, externalMsgId, since)
        .orElseThrow(() -> new IllegalStateException("Thông điệp trùng nhưng không đọc lại được"));
  }

  private Optional<Stored> latest(
      TenantId tenant, UUID connectorId, String externalMsgId, LocalDate since) {
    return jdbc.sql(
            "SELECT id, received_day, status FROM integ.inbox_message WHERE tenant_id = ?"
                + " AND connector_id = ? AND external_msg_id = ? AND received_day >= ?"
                + " ORDER BY received_day DESC, id DESC LIMIT 1")
        .params(tenant.value(), connectorId, externalMsgId, Date.valueOf(since))
        .query(
            (rs, i) ->
                new Stored(
                    rs.getObject(1, UUID.class),
                    rs.getDate(2).toLocalDate(),
                    InboxStatus.valueOf(rs.getString(3)),
                    false))
        .optional();
  }

  @Override
  public boolean processed(
      TenantId tenant, UUID connectorId, String externalMsgId, LocalDate since) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM integ.inbox_message WHERE tenant_id = ?"
                + " AND connector_id = ? AND external_msg_id = ? AND received_day >= ?"
                + " AND status IN ('DA_PHAT', 'DA_CHUYEN_DOI'))")
        .params(tenant.value(), connectorId, externalMsgId, Date.valueOf(since))
        .query(Boolean.class)
        .single();
  }

  @Override
  public Optional<Raw> find(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT id, received_day, connector_id, external_msg_id, message_type, raw_enc, status"
                + " FROM integ.inbox_message WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query(
            (rs, i) ->
                new Raw(
                    rs.getObject(1, UUID.class),
                    rs.getDate(2).toLocalDate(),
                    rs.getObject(3, UUID.class),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getBytes(6),
                    InboxStatus.valueOf(rs.getString(7))))
        .optional();
  }

  @Override
  public Optional<Raw> latestEmitted(TenantId tenant, UUID connectorId, String externalId) {
    String escaped = externalId.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return jdbc.sql(
            "SELECT id, received_day, connector_id, external_msg_id, message_type, raw_enc, status"
                + " FROM integ.inbox_message WHERE tenant_id = ? AND connector_id = ?"
                + " AND (external_msg_id = ? OR external_msg_id LIKE ?) AND status = 'DA_PHAT'"
                + " ORDER BY received_at DESC, id DESC LIMIT 1")
        .params(tenant.value(), connectorId, externalId, escaped + ":%")
        .query(
            (rs, i) ->
                new Raw(
                    rs.getObject(1, UUID.class),
                    rs.getDate(2).toLocalDate(),
                    rs.getObject(3, UUID.class),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getBytes(6),
                    InboxStatus.valueOf(rs.getString(7))))
        .optional();
  }

  @Override
  public void mark(
      TenantId tenant, LocalDate day, UUID id, InboxStatus status, UUID emittedEventId) {
    jdbc.sql(
            "UPDATE integ.inbox_message SET status = ?, emitted_event_id = ?"
                + " WHERE received_day = ? AND tenant_id = ? AND id = ?")
        .params(status.name(), emittedEventId, Date.valueOf(day), tenant.value(), id)
        .update();
  }

  @Override
  public boolean emitted(TenantId tenant, UUID connectorId, UUID eventId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM integ.inbox_message WHERE tenant_id = ?"
                + " AND connector_id = ? AND emitted_event_id = ?)")
        .params(tenant.value(), connectorId, eventId)
        .query(Boolean.class)
        .single();
  }
}
