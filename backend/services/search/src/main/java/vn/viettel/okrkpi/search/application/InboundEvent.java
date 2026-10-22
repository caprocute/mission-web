package vn.viettel.okrkpi.search.application;

import java.time.Instant;
import org.apache.avro.specific.SpecificRecord;

/**
 * Sự kiện đã giải mã từ Kafka.
 *
 * @param seq vị trí trong lô
 * @param tenantId tiêu đề {@code tenantid} của phong bì sự kiện
 * @param occurredAt tiêu đề {@code occurredat}, dùng đo độ trễ từ lúc phát tới lúc tìm được
 */
public record InboundEvent(
    int seq, String topic, String tenantId, SpecificRecord payload, Instant occurredAt) {}
