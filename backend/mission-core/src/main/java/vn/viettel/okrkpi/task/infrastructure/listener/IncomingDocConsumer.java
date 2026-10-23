package vn.viettel.okrkpi.task.infrastructure.listener;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListenerRegistrar;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.task.application.document.IncomingDocService;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Received;
import vn.vtokr.integ.v1.VanBanChiDaoDaNhan;

/**
 * Consumer EVT-24 {@code integ.document.received.v1} của MOD-02 (CORE-02-16, QT-05 bước 1b): ghi
 * văn bản chỉ đạo đến cho đơn vị nhận ở {@code CHO_XU_LY}. Inbox {@code task.inbox_processed} khử
 * trùng, {@code inbox_aggregate_version} theo {@code VAN_BAN} bỏ sự kiện cũ đến muộn; cách đăng ký
 * như {@link LeadMovedConsumer}.
 */
public class IncomingDocConsumer implements SmartLifecycle {

  static final String CONSUMER = "task.incoming-doc";
  static final String AGGREGATE = "VAN_BAN";

  private final IncomingDocService docs;
  private final InboxMessageProcessor processor;
  private final InboxEndpoint endpoint;
  private final InboxListenerRegistrar registrar;

  public IncomingDocConsumer(
      IncomingDocService docs,
      InboxMessageProcessor processor,
      ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>> containers,
      boolean autoStartup) {
    this.docs = Objects.requireNonNull(docs, "docs");
    this.processor = Objects.requireNonNull(processor, "processor");
    try {
      this.endpoint =
          new InboxEndpoint(
              EventTopics.EVT_24_VAN_BAN_CHI_DAO_DA_NHAN,
              CONSUMER,
              AGGREGATE,
              this,
              IncomingDocConsumer.class.getMethod("onReceived", InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
    this.registrar = new InboxListenerRegistrar(processor, containers, autoStartup);
    registrar.registerEndpoint(endpoint);
  }

  /** Áp một EVT-24. */
  public void onReceived(InboxEnvelope<VanBanChiDaoDaNhan> e) {
    VanBanChiDaoDaNhan d = e.payload();
    Instant at = e.occurredAt() == null ? docs.now() : e.occurredAt();
    docs.onReceived(
        e.tenantId(),
        new Received(
            d.getDocumentId(),
            sourceSystem(d.getConnectorCode()),
            d.getConnectorCode(),
            d.getExternalId(),
            d.getDocNumber(),
            d.getIssuedDate(),
            d.getIssuer(),
            d.getSummary(),
            d.getObjectKey(),
            d.getAggregateVersion(),
            e.eventId(),
            at));
  }

  /**
   * Hệ nguồn theo mã kết nối của SVC-13 (suy luận: mã kết nối mang tên hệ ngoài); Trục liên thông
   * và mã lạ là {@code EDXML}.
   */
  static String sourceSystem(String connectorCode) {
    String c = connectorCode == null ? "" : connectorCode.toUpperCase(Locale.ROOT);
    if (c.contains("VOFFICE")) {
      return "VOFFICE";
    }
    if (c.contains("IOFFICE")) {
      return "IOFFICE";
    }
    if (c.contains("LGSP")) {
      return "LGSP";
    }
    return "EDXML";
  }

  public InboxMessageProcessor processor() {
    return processor;
  }

  public InboxEndpoint endpoint() {
    return endpoint;
  }

  @Override
  public boolean isAutoStartup() {
    return registrar.isAutoStartup();
  }

  @Override
  public void start() {
    registrar.start();
  }

  @Override
  public void stop() {
    registrar.stop();
  }

  @Override
  public boolean isRunning() {
    return registrar.isRunning();
  }
}
