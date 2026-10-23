package vn.viettel.okrkpi.integ.infrastructure.anchor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.integ.application.anchor.AnchorService;
import vn.viettel.okrkpi.integ.application.anchor.SealNotice;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.vtokr.ledger.v1.SoCaiDaNiemPhong;

/**
 * Consumer EVT-19 {@code ledger.seal.created.v1} của luồng neo: giải mã Avro, đưa niêm phong cho
 * {@link AnchorService#onSeal}. Chống trùng bằng UQ ({@code tenant_id}, {@code cell_code}, {@code
 * window_end}) của {@code anchor_window}: mọi tenant của cell cùng ngày và mọi lần giao lại chỉ ghi
 * một dòng.
 */
public class AnchorSealRoute extends RouteBuilder {

  static final String GROUP = "integ-anchor";

  private final AnchorService anchors;
  private final String brokers;

  public AnchorSealRoute(AnchorService anchors, String brokers) {
    this.anchors = anchors;
    this.brokers = brokers;
  }

  @Override
  public void configure() {
    errorHandler(
        defaultErrorHandler()
            .maximumRedeliveries(5)
            .redeliveryDelay(1000)
            .backOffMultiplier(2)
            .useExponentialBackOff()
            .retryAttemptedLogLevel(LoggingLevel.WARN)
            .logExhaustedMessageBody(false)
            .logStackTrace(false));
    from("kafka:"
            + EventTopics.EVT_19_SO_CAI_DA_NIEM_PHONG
            + "?brokers="
            + brokers
            + "&groupId="
            + GROUP
            + "&autoOffsetReset=earliest"
            + "&valueDeserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer")
        .routeId(GROUP + "-seal")
        .process(this::onSeal);
  }

  void onSeal(Exchange e) throws IOException {
    Object id = e.getMessage().getHeader(OutboxHeaders.EVENT_ID);
    String eventId = id instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : (String) id;
    if (eventId == null) {
      throw new IllegalArgumentException("Sự kiện thiếu header id");
    }
    anchors.onSeal(notice(UUID.fromString(eventId), e.getMessage().getBody(byte[].class)));
  }

  static SealNotice notice(UUID eventId, byte[] body) throws IOException {
    EventContracts.trustContractPackages();
    SoCaiDaNiemPhong seal =
        new SpecificDatumReader<>(SoCaiDaNiemPhong.class)
            .read(null, DecoderFactory.get().binaryDecoder(body, null));
    return new SealNotice(
        eventId, seal.getSealId(), seal.getSealKind(), seal.getWindowStart(), seal.getWindowEnd());
  }
}
