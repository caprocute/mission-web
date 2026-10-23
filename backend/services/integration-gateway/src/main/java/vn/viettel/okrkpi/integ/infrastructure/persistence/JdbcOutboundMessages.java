package vn.viettel.okrkpi.integ.infrastructure.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.integ.application.port.OutboundMessages;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher.Sealed;
import vn.viettel.okrkpi.integ.domain.OutboundStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@code integ.outbound_message} (phân vùng ngày); việc đến hạn qua hàm quét liên tenant. */
public class JdbcOutboundMessages implements OutboundMessages {

  private static final String COLUMNS =
      "id, created_day, connector_id, source_event_id, message_type, payload_enc, status,"
          + " attempts, ack_ref";

  private final JdbcClient jdbc;

  public JdbcOutboundMessages(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean exists(TenantId tenant, UUID connectorId, UUID sourceEventId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM integ.outbound_message WHERE tenant_id = ?"
                + " AND connector_id = ? AND source_event_id = ?)")
        .params(tenant.value(), connectorId, sourceEventId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public Optional<Tracked> bySource(TenantId tenant, UUID sourceEventId) {
    return jdbc.sql(
            "SELECT id, connector_id, status, attempts, next_attempt_at, ack_ref, updated_at"
                + " FROM integ.outbound_message WHERE tenant_id = ? AND source_event_id = ?"
                + " ORDER BY created_at DESC LIMIT 1")
        .params(tenant.value(), sourceEventId)
        .query(
            (rs, i) -> {
              Timestamp next = rs.getTimestamp(5);
              return new Tracked(
                  rs.getObject(1, UUID.class),
                  rs.getObject(2, UUID.class),
                  OutboundStatus.valueOf(rs.getString(3)),
                  rs.getInt(4),
                  next == null ? null : next.toInstant(),
                  rs.getString(6),
                  rs.getTimestamp(7).toInstant());
            })
        .optional();
  }

  @Override
  public List<Due> due(int limit, java.util.Set<String> codes, boolean include) {
    if (codes.isEmpty() && !include) {
      return due(limit);
    }
    return jdbc.sql(
            "SELECT tenant_id, created_day, id FROM integ.fn_thu_ra_den_han_theo_ma(?, ?, ?)")
        .params(limit, codes.toArray(String[]::new), include)
        .query(
            (rs, i) ->
                new Due(
                    rs.getObject(1, UUID.class),
                    rs.getDate(2).toLocalDate(),
                    rs.getObject(3, UUID.class)))
        .list();
  }

  @Override
  public void insert(
      TenantId tenant,
      UUID id,
      LocalDate day,
      UUID connectorId,
      UUID sourceEventId,
      String messageType,
      Sealed payload,
      Instant nextAttemptAt) {
    jdbc.sql(
            "INSERT INTO integ.outbound_message (created_day, id, tenant_id, connector_id,"
                + " source_event_id, message_type, payload_enc, dek_id, status, attempts,"
                + " next_attempt_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CHO', 0, ?)")
        .params(
            Date.valueOf(day),
            id,
            tenant.value(),
            connectorId,
            sourceEventId,
            messageType,
            payload.ciphertext(),
            payload.dekId(),
            Timestamp.from(nextAttemptAt))
        .update();
  }

  @Override
  public List<Due> due(int limit) {
    return jdbc.sql("SELECT tenant_id, created_day, id FROM integ.fn_thu_ra_den_han(?)")
        .param(limit)
        .query(
            (rs, i) ->
                new Due(
                    rs.getObject(1, UUID.class),
                    rs.getDate(2).toLocalDate(),
                    rs.getObject(3, UUID.class)))
        .list();
  }

  @Override
  public Optional<Row> claim(TenantId tenant, LocalDate day, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM integ.outbound_message WHERE created_day = ? AND tenant_id = ? AND id = ?"
                + " AND status IN ('CHO', 'DA_GUI') AND next_attempt_at <= now()"
                + " FOR UPDATE SKIP LOCKED")
        .params(Date.valueOf(day), tenant.value(), id)
        .query(JdbcOutboundMessages::map)
        .optional();
  }

  @Override
  public Optional<Row> find(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM integ.outbound_message WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query(JdbcOutboundMessages::map)
        .optional();
  }

  @Override
  public void update(
      TenantId tenant,
      LocalDate day,
      UUID id,
      OutboundStatus status,
      int attempts,
      Instant nextAttemptAt,
      String ackRef) {
    jdbc.sql(
            "UPDATE integ.outbound_message SET status = ?, attempts = ?, next_attempt_at = ?,"
                + " ack_ref = ?, updated_at = now()"
                + " WHERE created_day = ? AND tenant_id = ? AND id = ?")
        .params(
            status.name(),
            attempts,
            nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt),
            ackRef,
            Date.valueOf(day),
            tenant.value(),
            id)
        .update();
  }

  private static Row map(ResultSet rs, int row) throws SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getDate("created_day").toLocalDate(),
        rs.getObject("connector_id", UUID.class),
        rs.getObject("source_event_id", UUID.class),
        rs.getString("message_type"),
        rs.getBytes("payload_enc"),
        OutboundStatus.valueOf(rs.getString("status")),
        rs.getInt("attempts"),
        rs.getString("ack_ref"));
  }
}
