package vn.viettel.okrkpi.org.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.org.application.OutboxEvent;
import vn.viettel.okrkpi.org.application.port.OutboxPort;

/**
 * Ghi {@code org.outbox_event} theo cột TL-03 trong giao dịch đang mở (tương đương {@code Propagation.MANDATORY}).
 * <p>
 * Tạm thời (điểm chờ NEN-007, NEN-011): payload tuần tự hoá JSON UTF-8 với {@code schema_version = 0} và header
 * {@code contentType = application/json}; khi có {@code OutboxEventPublisher} của thư viện nền, payload chuyển sang
 * Avro qua serde Apicurio và {@code schema_version} lấy từ registry. Debezium (NEN-022) đọc bảng này từ WAL.
 */
@Component
public class JdbcOutboxWriter implements OutboxPort {

  /** Đánh dấu payload JSON tạm thời, chưa đăng ký lược đồ Avro. */
  public static final int TEMPORARY_JSON_SCHEMA_VERSION = 0;

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcOutboxWriter(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void append(OutboxEvent e) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException("Outbox chỉ ghi trong giao dịch nghiệp vụ đang mở");
    }
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", e.eventId().toString());
    envelope.put("eventType", e.eventType());
    envelope.put("tenantId", e.tenantId().toString());
    envelope.put("occurredAt", e.occurredAt().toString());
    envelope.put("actorId", e.actorId() == null ? null : e.actorId().toString());
    envelope.put("traceparent", e.traceparent());
    envelope.put("classification", e.classification());
    envelope.put("aggregateVersion", e.aggregateVersion());
    envelope.put("data", e.payload());
    Map<String, Object> headers = new LinkedHashMap<>();
    headers.put("traceparent", e.traceparent());
    headers.put("actorId", e.actorId() == null ? null : e.actorId().toString());
    headers.put("tenantId", e.tenantId().toString());
    headers.put("classification", e.classification());
    headers.put("occurredAt", e.occurredAt().toString());
    headers.put("aggregateVersion", e.aggregateVersion());
    headers.put("ce_type", e.eventType());
    headers.put("contentType", "application/json");
    try {
      jdbc.sql("INSERT INTO org.outbox_event (id, tenant_id, aggregate_type, aggregate_id, aggregate_version, "
              + "event_type, topic, event_key, payload, schema_version, headers) VALUES (:id, :tenant, :atype, :aid, "
              + ":aver, :etype, :topic, :key, :payload, :sver, CAST(:headers AS jsonb))")
          .param("id", e.eventId()).param("tenant", e.tenantId()).param("atype", e.aggregateType())
          .param("aid", e.aggregateId()).param("aver", e.aggregateVersion()).param("etype", e.eventType())
          .param("topic", e.topic()).param("key", e.eventKey()).param("payload", json.writeValueAsBytes(envelope))
          .param("sver", TEMPORARY_JSON_SCHEMA_VERSION).param("headers", json.writeValueAsString(headers))
          .update();
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
