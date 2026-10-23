package vn.viettel.okrkpi.integ.infrastructure.camel;

import io.micrometer.core.instrument.Metrics;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.integ.application.OutboundBinding;
import vn.viettel.okrkpi.integ.application.OutboundPipeline;
import vn.viettel.okrkpi.integ.application.outbound.OutboundRejectedException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;

/**
 * Consumer sự kiện nội bộ cần gửi ra ngoài (EVT-12, EVT-19...): đọc {@code tenantid} và mã sự kiện
 * {@code id} từ header Kafka, định dạng theo hệ ngoài rồi ghi {@code outbound_message}; mã sự kiện
 * là khoá chống trùng. Sự kiện bị chính sách chia sẻ từ chối ({@link OutboundRejectedException}:
 * vượt mức phân loại, thiếu mã ngoài, đến sai thứ tự) không thử lại: ghi nhật ký lý do (không có dữ
 * liệu) và metric {@code integ_outbound_rejected_total} rồi bỏ qua.
 */
public class OutboundEventRoute extends RouteBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(OutboundEventRoute.class);

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
    onException(OutboundRejectedException.class).handled(true).process(this::rejected);
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

  void rejected(Exchange e) {
    OutboundRejectedException ex =
        e.getProperty(Exchange.EXCEPTION_CAUGHT, OutboundRejectedException.class);
    String reason = ex == null ? "UNKNOWN" : ex.reason().name();
    LOG.warn("Không gửi sự kiện {} tới {}: {}", binding.topic(), binding.connectorCode(), reason);
    Metrics.counter(
            "integ_outbound_rejected_total", "connector", binding.connectorCode(), "reason", reason)
        .increment();
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
