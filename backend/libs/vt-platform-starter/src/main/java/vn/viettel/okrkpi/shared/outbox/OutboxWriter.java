package vn.viettel.okrkpi.shared.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;

/**
 * NEN-011 · ADR-03 · Cách duy nhất để mã Java phát sự kiện (TL-03 "Transactional Outbox và
 * Debezium", baseline mục 8.1, BACKEND.md mục 3 quy ước 4): tuần tự hoá thân Avro theo lược đồ
 * Apicurio rồi chèn một dòng vào {@code <schema>.outbox_event} <b>trong giao dịch nghiệp vụ đang
 * mở</b>. Debezium đọc WAL và đẩy lên Kafka; mã nghiệp vụ không bao giờ gửi Kafka trực tiếp, nên sự
 * kiện chỉ tồn tại khi giao dịch commit.
 *
 * <p>Gọi ngoài giao dịch ném {@link IllegalTransactionStateException} (cả khi bean không được bọc
 * proxy giao dịch). Ngữ cảnh tenant ({@code app.tenant_id}) do giao dịch nghiệp vụ đặt (NEN-010);
 * dòng outbox của tenant khác tenant đang đặt bị RLS của NEN-032 chặn.
 */
public class OutboxWriter {

  /** Tên schema hợp lệ: chữ thường, số, gạch dưới (TL-03 "Quy ước đặt tên"). */
  static final Pattern SCHEMA_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

  private final JdbcTemplate jdbc;
  private final OutboxPayloadSerializer serializer;
  private final ObjectMapper json;
  private final Clock clock;
  private final String schema;
  private final String insertSql;

  public OutboxWriter(
      JdbcTemplate jdbc,
      String schema,
      OutboxPayloadSerializer serializer,
      ObjectMapper json,
      Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.serializer = Objects.requireNonNull(serializer, "serializer");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.schema = requireSchema(schema);
    this.insertSql = insertSql(this.schema);
  }

  /**
   * Ghi sự kiện vào outbox của schema mô-đun, trong giao dịch đang mở của bên gọi.
   *
   * @return mã sự kiện (cột {@code id})
   * @throws IllegalTransactionStateException khi không có giao dịch đang mở
   * @throws OutboxSerializationException khi không tra được lược đồ hoặc không tuần tự hoá được
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID append(EventEnvelope event) {
    Objects.requireNonNull(event, "event");
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException(
          "OutboxWriter.append phải chạy trong giao dịch nghiệp vụ đang mở (Propagation.MANDATORY)");
    }
    SerializedPayload payload = serializer.serialize(event.topic(), event.payload());
    jdbc.update(
        insertSql,
        event.eventId(),
        event.tenantId().value(),
        event.aggregateType(),
        event.aggregateId(),
        event.aggregateVersion(),
        event.eventType(),
        event.topic(),
        event.eventKey(),
        payload.bytes(),
        payload.schemaVersion(),
        headersJson(event, payload));
    return event.eventId();
  }

  /** Schema chứa bảng outbox mà bean này ghi vào ({@code okrkpi.outbox.schema}). */
  public String schema() {
    return schema;
  }

  /** Tiêu đề sự kiện theo {@link OutboxHeaders}; thứ tự khoá cố định để dễ đối chiếu. */
  Map<String, Object> headers(EventEnvelope event, SerializedPayload payload) {
    Map<String, Object> headers = new LinkedHashMap<>();
    String traceparent = currentTraceparent();
    if (traceparent != null) {
      headers.put(OutboxHeaders.TRACEPARENT, traceparent);
    }
    if (event.actorId() != null) {
      headers.put(OutboxHeaders.ACTOR_ID, event.actorId().toString());
    }
    headers.put(OutboxHeaders.TENANT_ID, event.tenantId().toString());
    headers.put(OutboxHeaders.CLASSIFICATION, event.classification().name());
    if (event.policyVersion() != null) {
      headers.put(OutboxHeaders.POLICY_VERSION, event.policyVersion());
    }
    headers.put(OutboxHeaders.AGGREGATE_VERSION, Long.toString(event.aggregateVersion()));
    headers.put(OutboxHeaders.CE_TYPE, event.eventType());
    Instant occurredAt = event.occurredAt() != null ? event.occurredAt() : clock.instant();
    headers.put(OutboxHeaders.OCCURRED_AT, occurredAt.toString());
    headers.put(OutboxHeaders.APICURIO_GLOBAL_ID, payload.globalId());
    return headers;
  }

  private String headersJson(EventEnvelope event, SerializedPayload payload) {
    try {
      return json.writeValueAsString(headers(event, payload));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không ghi được tiêu đề sự kiện dạng JSON", e);
    }
  }

  /** {@code traceparent} W3C của span hiện hành, {@code null} khi không có span hợp lệ. */
  static String currentTraceparent() {
    Map<String, String> carrier = new LinkedHashMap<>();
    W3CTraceContextPropagator.getInstance()
        .inject(Context.current(), carrier, (c, k, v) -> c.put(k, v));
    return carrier.get(OutboxHeaders.TRACEPARENT);
  }

  static String requireSchema(String schema) {
    if (schema == null || !SCHEMA_PATTERN.matcher(schema).matches()) {
      throw new IllegalArgumentException(
          "okrkpi.outbox.schema phải là tên schema của mô-đun (chữ thường, số, gạch dưới): "
              + schema);
    }
    return schema;
  }

  private static String insertSql(String schema) {
    return "INSERT INTO "
        + schema
        + ".outbox_event (id, tenant_id, aggregate_type, aggregate_id, aggregate_version,"
        + " event_type, topic, event_key, payload, schema_version, headers)"
        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)";
  }
}
