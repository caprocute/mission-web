package vn.viettel.okrkpi.shared.inbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * NEN-012 · ADR-03 · Phong bì sự kiện đã giải mã trao cho phương thức {@link InboxListener}.
 *
 * @param eventId mã sự kiện (tiêu đề {@code id}, cột {@code event_id} trong {@code
 *     inbox_processed})
 * @param topic topic nguồn của sự kiện
 * @param eventKey khoá thông điệp Kafka ({@code tenantId:aggregateId})
 * @param tenantId tenant sở hữu sự kiện (tiêu đề {@code tenantid})
 * @param aggregateId mã thực thể gốc (tách từ khoá thông điệp hoặc thân sự kiện; có thể {@code
 *     null} khi consumer không kiểm phiên bản aggregate)
 * @param aggregateVersion phiên bản thực thể sau thay đổi (tiêu đề {@code aggregateversion} hoặc
 *     trường cùng tên trong thân)
 * @param eventType tên nghiệp vụ của sự kiện (tiêu đề {@code ce_type})
 * @param classification phân loại dữ liệu (tiêu đề {@code classification})
 * @param actorId người thực hiện (tiêu đề {@code actorid}, có thể {@code null})
 * @param policyVersion phiên bản Policy Pack (tiêu đề {@code policyversion}, có thể {@code null})
 * @param traceparent W3C Trace Context (tiêu đề {@code traceparent}, có thể {@code null})
 * @param occurredAt thời điểm xảy ra (tiêu đề {@code occurredat}, có thể {@code null})
 * @param partition phân vùng Kafka
 * @param offset offset Kafka
 * @param payload thân sự kiện Avro đã giải tuần tự
 * @param <T> kiểu lớp Avro sinh từ hợp đồng sự kiện
 */
public record InboxEnvelope<T extends SpecificRecord>(
    UUID eventId,
    String topic,
    String eventKey,
    TenantId tenantId,
    UUID aggregateId,
    long aggregateVersion,
    String eventType,
    Classification classification,
    ActorId actorId,
    String policyVersion,
    String traceparent,
    Instant occurredAt,
    int partition,
    long offset,
    T payload) {

  public InboxEnvelope {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(classification, "classification");
    Objects.requireNonNull(payload, "payload");
  }
}
