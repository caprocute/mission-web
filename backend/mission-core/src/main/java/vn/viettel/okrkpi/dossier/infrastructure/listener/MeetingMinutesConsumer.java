package vn.viettel.okrkpi.dossier.infrastructure.listener;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import vn.viettel.okrkpi.dossier.application.meeting.MeetingProceedingsService;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListenerRegistrar;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.vtokr.dossier.v1.HoSoSoDaSinh;
import vn.vtokr.esign.v1.KySoDaHoanTat;
import vn.vtokr.esign.v1.SignedItem;

/**
 * Consumer biên bản hội nghị kiểm điểm của MOD-06 (CORE-06-04): EVT-17 {@code
 * dossier.form.generated.v1} của biên bản (mã biểu mẫu là mã hội nghị) và EVT-18 {@code
 * esign.signature.completed.v1} mục đích {@code BIEN_BAN} nguồn {@code dossier}. Inbox ghi {@code
 * dossier.inbox_processed} bằng {@code dossierDataSource} nên consumer tự giữ bộ xử lý inbox và bộ
 * đăng ký riêng như consumer EVT-18 của MOD-02. Sự kiện trùng do inbox chặn; sự kiện lặp với mã
 * khác cũng không đổi gì vì dịch vụ kiểm trạng thái và người đã ký.
 */
public class MeetingMinutesConsumer implements SmartLifecycle {

  static final String CONSUMER_GENERATED = "dossier.meeting-minutes-generated";
  static final String CONSUMER_SIGNED = "dossier.meeting-minutes-signed";
  static final String SOURCE_MODULE = "dossier";
  static final String PURPOSE = "BIEN_BAN";

  private final MeetingProceedingsService proceedings;
  private final InboxMessageProcessor processor;
  private final InboxEndpoint generated;
  private final InboxEndpoint signed;
  private final InboxListenerRegistrar registrar;

  public MeetingMinutesConsumer(
      MeetingProceedingsService proceedings,
      InboxMessageProcessor processor,
      ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>> containers,
      boolean autoStartup) {
    this.proceedings = Objects.requireNonNull(proceedings, "proceedings");
    this.processor = Objects.requireNonNull(processor, "processor");
    try {
      this.generated =
          new InboxEndpoint(
              EventTopics.EVT_17_HO_SO_SO_DA_SINH,
              CONSUMER_GENERATED,
              "",
              this,
              MeetingMinutesConsumer.class.getMethod("onGenerated", InboxEnvelope.class));
      this.signed =
          new InboxEndpoint(
              EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
              CONSUMER_SIGNED,
              "",
              this,
              MeetingMinutesConsumer.class.getMethod("onSigned", InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
    this.registrar = new InboxListenerRegistrar(processor, containers, autoStartup);
    registrar.registerEndpoint(generated);
    registrar.registerEndpoint(signed);
  }

  /** EVT-17: biểu mẫu không phải biên bản hội nghị được dịch vụ bỏ qua. */
  public void onGenerated(InboxEnvelope<HoSoSoDaSinh> e) {
    HoSoSoDaSinh payload = e.payload();
    proceedings.onMinutesGenerated(
        e.tenantId(),
        new MeetingProceedingsService.Generated(
            payload.getRenderJobId(),
            payload.getFormInstanceId(),
            payload.getObjectKey(),
            bytes(payload.getPdfSha256())));
  }

  /** EVT-18: chỉ chữ ký biên bản của nguồn {@code dossier}. */
  public void onSigned(InboxEnvelope<KySoDaHoanTat> e) {
    KySoDaHoanTat payload = e.payload();
    if (!SOURCE_MODULE.equals(payload.getSourceModule()) || !PURPOSE.equals(payload.getPurpose())) {
      return;
    }
    UUID signer = e.actorId() == null ? null : e.actorId().value();
    List<byte[]> hashes =
        payload.getItems().stream().map(SignedItem::getDocumentSha256).map(this::bytes).toList();
    proceedings.onSigned(
        e.tenantId(),
        new MeetingProceedingsService.Signed(
            payload.getSignRequestId(),
            payload.getSourceRefId(),
            signer,
            hashes,
            payload.getSignedAt()));
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

  public InboxEndpoint generatedEndpoint() {
    return generated;
  }

  public InboxEndpoint signedEndpoint() {
    return signed;
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
