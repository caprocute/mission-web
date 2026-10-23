package vn.viettel.okrkpi.integ.application;

import java.util.Objects;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import vn.viettel.okrkpi.shared.kernel.Classification;

/**
 * Sự kiện mô hình dữ liệu chuẩn (tám nhóm PL 4) mà translator sinh ra; Publisher ghi vào outbox
 * {@code integ.outbox_event}.
 */
public record CanonicalEvent(
    String topic,
    String aggregateType,
    UUID aggregateId,
    long aggregateVersion,
    SpecificRecord payload,
    Classification classification) {

  public CanonicalEvent {
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(aggregateType, "aggregateType");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(payload, "payload");
    classification = classification == null ? Classification.C1 : classification;
  }
}
