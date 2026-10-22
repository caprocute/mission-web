package vn.viettel.okrkpi.search.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import vn.viettel.okrkpi.search.application.InboundEvent;
import vn.viettel.okrkpi.search.domain.IndexNames;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;

/** Đọc phong bì sự kiện (tiêu đề {@code tenantid}, {@code occurredat} của NEN-012) và thân Avro. */
public final class EventDecoder {

  private final InboxPayloadDeserializer payloads;

  public EventDecoder(InboxPayloadDeserializer payloads) {
    this.payloads = payloads;
  }

  public InboundEvent decode(int seq, ConsumerRecord<String, byte[]> r) {
    String tenant = header(r, OutboxHeaders.TENANT_ID);
    try {
      tenant = IndexNames.tenant(tenant == null ? "" : tenant);
    } catch (IllegalArgumentException e) {
      throw new PermanentInboxException(
          PermanentInboxException.INVALID_HEADER_CODE,
          "Thiếu hoặc sai tiêu đề tenantid trên topic " + r.topic());
    }
    Instant at = null;
    String occurred = header(r, OutboxHeaders.OCCURRED_AT);
    if (occurred != null) {
      try {
        at = Instant.parse(occurred);
      } catch (DateTimeParseException e) {
        at = null;
      }
    }
    return new InboundEvent(
        seq, r.topic(), tenant, payloads.deserialize(r.topic(), r.headers(), r.value()), at);
  }

  private static String header(ConsumerRecord<?, ?> r, String name) {
    Header h = r.headers().lastHeader(name);
    return h == null || h.value() == null
        ? null
        : new String(h.value(), StandardCharsets.UTF_8).trim();
  }
}
