package vn.viettel.okrkpi.integ.infrastructure.camel;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import vn.viettel.okrkpi.integ.application.OutboundBinding;
import vn.viettel.okrkpi.integ.application.OutboundPipeline;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;

/**
 * Consumer sự kiện nội bộ cần gửi ra ngoài (EVT-12, EVT-19...): đọc {@code tenantid} và mã sự kiện
 * {@code id} từ header Kafka, định dạng theo hệ ngoài rồi ghi {@code outbound_message}; mã sự kiện
 * là khoá chống trùng.
 */
public class OutboundEventRoute extends RouteBuilder {

  private final OutboundBinding binding;
  private final OutboundPipeline pipeline;
  private final String brokers;

  public OutboundEventRoute(OutboundBinding binding, OutboundPipeline pipeline, String brokers) {
    this.binding = binding;
    this.pipeline = pipeline;
    this.brokers = brokers;
  }

  @Override
  public void configure() {
    String id = "integ-out-" + binding.connectorCode().toLowerCase(Locale.ROOT);
    errorHandler(
        defaultErrorHandler()
            .maximumRedeliveries(3)
            .redeliveryDelay(1000)
            .retryAttemptedLogLevel(LoggingLevel.WARN)
            .logExhaustedMessageBody(false)
            .logStackTrace(false));
    from("kafka:"
            + binding.topic()
            + "?brokers="
            + brokers
            + "&groupId="
            + id
            + "&autoOffsetReset=earliest"
            + "&valueDeserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer")
        .routeId(id + "-" + binding.topic())
        .process(this::enqueue);
  }

  void enqueue(Exchange e) {
    Map<String, String> headers = new HashMap<>();
    e.getMessage()
        .getHeaders()
        .forEach(
            (k, v) -> {
              if (v instanceof byte[] b) {
                headers.put(k, new String(b, StandardCharsets.UTF_8));
              } else if (v instanceof String s) {
                headers.put(k, s);
              }
            });
    String tenant = headers.get(OutboxHeaders.TENANT_ID);
    String eventId = headers.get(OutboxHeaders.EVENT_ID);
    if (tenant == null || eventId == null) {
      throw new IllegalArgumentException("Sự kiện thiếu header tenantid hoặc id");
    }
    byte[] body = e.getMessage().getBody(byte[].class);
    pipeline.enqueue(
        TenantId.parse(tenant),
        binding.connectorCode(),
        UUID.fromString(eventId),
        binding.messageType(),
        binding.format(body == null ? new byte[0] : body, headers));
  }
}
