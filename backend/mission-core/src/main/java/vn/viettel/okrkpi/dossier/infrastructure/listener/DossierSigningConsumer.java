package vn.viettel.okrkpi.dossier.infrastructure.listener;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierSigningService;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListenerRegistrar;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.vtokr.esign.v1.KySoDaHoanTat;
import vn.vtokr.esign.v1.SignedItem;
import vn.vtokr.oversight.v1.KyDaMoLaiHauKiem;

/**
 * Consumer ký và mở lại gói hồ sơ số của MOD-06 (CORE-06-07): EVT-18 {@code
 * esign.signature.completed.v1} mục đích {@code GOI_HO_SO} nguồn {@code dossier} nộp và khoá gói;
 * EVT-22 {@code oversight.period.reopened.v1} lập phiên bản gói mới. Inbox {@code dossier} chặn sự
 * kiện trùng mã; sự kiện lặp với mã khác không đổi gì vì dịch vụ kiểm trạng thái gói và phiên bản
 * mới nhất.
 */
public class DossierSigningConsumer implements SmartLifecycle {

  static final String CONSUMER_SIGNED = "dossier.package-signed";
  static final String CONSUMER_REOPENED = "dossier.package-period-reopened";
  static final String SOURCE_MODULE = "dossier";
  static final String PURPOSE = "GOI_HO_SO";

  private final DossierSigningService signing;
  private final InboxMessageProcessor processor;
  private final InboxEndpoint signed;
  private final InboxEndpoint reopened;
  private final InboxListenerRegistrar registrar;

  public DossierSigningConsumer(
      DossierSigningService signing,
      InboxMessageProcessor processor,
      ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>> containers,
      boolean autoStartup) {
    this.signing = Objects.requireNonNull(signing, "signing");
    this.processor = Objects.requireNonNull(processor, "processor");
    try {
      this.signed =
          new InboxEndpoint(
              EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
              CONSUMER_SIGNED,
              "",
              this,
              DossierSigningConsumer.class.getMethod("onSigned", InboxEnvelope.class));
      this.reopened =
          new InboxEndpoint(
              EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM,
              CONSUMER_REOPENED,
              "",
              this,
              DossierSigningConsumer.class.getMethod("onReopened", InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
    this.registrar = new InboxListenerRegistrar(processor, containers, autoStartup);
    registrar.registerEndpoint(signed);
    registrar.registerEndpoint(reopened);
  }

  /** EVT-18: chỉ chữ ký gói của nguồn {@code dossier}. */
  public void onSigned(InboxEnvelope<KySoDaHoanTat> e) {
    KySoDaHoanTat payload = e.payload();
    if (!SOURCE_MODULE.equals(payload.getSourceModule()) || !PURPOSE.equals(payload.getPurpose())) {
      return;
    }
    List<byte[]> hashes =
        payload.getItems().stream().map(SignedItem::getDocumentSha256).map(this::bytes).toList();
    signing.onSigned(
        e.tenantId(),
        new DossierSigningService.Signed(
            payload.getSignRequestId(),
            payload.getSourceRefId(),
            e.actorId() == null ? null : e.actorId().value(),
            hashes,
            payload.getSignedAt(),
            payload.getItems().stream()
                .map(i -> i.getSignedObjectKey() == null ? null : i.getSignedObjectKey().toString())
                .toList()));
  }

  /** EVT-22: kỳ mở lại hậu kiểm. */
  public void onReopened(InboxEnvelope<KyDaMoLaiHauKiem> e) {
    KyDaMoLaiHauKiem payload = e.payload();
    UUID actor =
        e.actorId() != null
            ? e.actorId().value()
            : payload.getApproverIds().isEmpty() ? null : payload.getApproverIds().get(0);
    signing.onReopened(
        e.tenantId(),
        new DossierSigningService.Reopened(
            payload.getPeriodId(), payload.getScope(), List.copyOf(payload.getScopeIds()), actor));
  }

  private byte[] bytes(ByteBuffer buffer) {
    if (buffer == null) {
      return null;
    }
    ByteBuffer copy = buffer.duplicate();
    byte[] out = new byte[copy.remaining()];
    copy.get(out);
    return out;
  }

  public InboxMessageProcessor processor() {
    return processor;
  }

  public InboxEndpoint signedEndpoint() {
    return signed;
  }

  public InboxEndpoint reopenedEndpoint() {
    return reopened;
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
