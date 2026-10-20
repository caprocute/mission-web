package vn.viettel.okrkpi.org.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sự kiện chờ ghi vào outbox. Phong bì theo baseline mục 7 và NEN-011: {@code eventId}, {@code tenantId},
 * {@code occurredAt}, {@code actorId}, {@code traceparent}, {@code classification}. Không mang trường C2 dạng rõ.
 */
public record OutboxEvent(
    UUID eventId,
    String topic,
    String eventType,
    String aggregateType,
    UUID aggregateId,
    long aggregateVersion,
    UUID tenantId,
    UUID actorId,
    Instant occurredAt,
    String traceparent,
    String classification,
    Map<String, Object> payload) {

  public OutboxEvent {
    if (!"C0".equals(classification) && !"C1".equals(classification)) {
      throw new IllegalArgumentException("Sự kiện không được mang dữ liệu C2, C3");
    }
    payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
  }

  /** Khoá Kafka {@code tenantId:aggregateId} (topic compact theo khoá). */
  public String eventKey() {
    return tenantId + ":" + aggregateId;
  }
}
