package vn.viettel.okrkpi.integ.infrastructure.events;

import java.util.UUID;
import vn.viettel.okrkpi.integ.application.CanonicalEvent;
import vn.viettel.okrkpi.integ.application.port.CanonicalEventSink;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * Publisher của lớp chống ô nhiễm: ghi {@code integ.outbox_event} trong giao dịch đang mở; Debezium
 * đẩy lên Kafka nội bộ (ví dụ EVT-24 {@code integ.document.received.v1}).
 */
public class OutboxCanonicalEventSink implements CanonicalEventSink {

  private final OutboxWriter outbox;

  public OutboxCanonicalEventSink(OutboxWriter outbox) {
    this.outbox = outbox;
  }

  @Override
  public UUID publish(TenantId tenant, CanonicalEvent e) {
    return outbox.append(
        EventEnvelope.of(
            e.topic(),
            tenant,
            e.aggregateType(),
            e.aggregateId(),
            e.aggregateVersion(),
            e.payload(),
            e.classification()));
  }
}
