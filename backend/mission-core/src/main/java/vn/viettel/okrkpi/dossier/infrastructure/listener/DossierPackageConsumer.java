package vn.viettel.okrkpi.dossier.infrastructure.listener;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListenerRegistrar;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.vtokr.appraisal.v1.XepLoaiDaQuyetDinh;
import vn.vtokr.dossier.v1.HoSoSoDaSinh;

/**
 * Consumer gói hồ sơ số của MOD-06 (CORE-06-06): EVT-12 {@code appraisal.rating.decided.v1} lập gói
 * và ghi hồ sơ vào danh mục chờ sinh; EVT-17 {@code dossier.form.generated.v1} của biểu mẫu chính
 * thức ghi tệp đã sinh. Inbox {@code dossier} chặn sự kiện trùng mã; sự kiện lặp với mã khác cũng
 * không đổi gì vì dịch vụ kiểm gói đã có và biểu mẫu đã sinh. EVT-17 của biên bản hội nghị (mã biểu
 * mẫu là mã hội nghị) do {@code MeetingMinutesConsumer} xử lý, ở đây được bỏ qua.
 */
public class DossierPackageConsumer implements SmartLifecycle {

  static final String CONSUMER_DECIDED = "dossier.package-rating-decided";
  static final String CONSUMER_GENERATED = "dossier.package-form-generated";

  private final DossierPackageService packages;
  private final InboxMessageProcessor processor;
  private final InboxEndpoint decided;
  private final InboxEndpoint generated;
  private final InboxListenerRegistrar registrar;

  public DossierPackageConsumer(
      DossierPackageService packages,
      InboxMessageProcessor processor,
      ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>> containers,
      boolean autoStartup) {
    this.packages = Objects.requireNonNull(packages, "packages");
    this.processor = Objects.requireNonNull(processor, "processor");
    try {
      this.decided =
          new InboxEndpoint(
              EventTopics.EVT_12_XEP_LOAI_DA_QUYET_DINH,
              CONSUMER_DECIDED,
              "",
              this,
              DossierPackageConsumer.class.getMethod("onDecided", InboxEnvelope.class));
      this.generated =
          new InboxEndpoint(
              EventTopics.EVT_17_HO_SO_SO_DA_SINH,
              CONSUMER_GENERATED,
              "",
              this,
              DossierPackageConsumer.class.getMethod("onGenerated", InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
    this.registrar = new InboxListenerRegistrar(processor, containers, autoStartup);
    registrar.registerEndpoint(decided);
    registrar.registerEndpoint(generated);
  }

  /** EVT-12: hồ sơ đã có quyết định xếp loại. */
  public void onDecided(InboxEnvelope<XepLoaiDaQuyetDinh> e) {
    XepLoaiDaQuyetDinh payload = e.payload();
    UUID actor = e.actorId() == null ? payload.getDeciderId() : e.actorId().value();
    packages.onRatingDecided(e.tenantId(), payload.getProfileId(), actor);
  }

  /** EVT-17: biểu mẫu chính thức đã sinh. */
  public void onGenerated(InboxEnvelope<HoSoSoDaSinh> e) {
    HoSoSoDaSinh payload = e.payload();
    packages.onFormGenerated(
        e.tenantId(),
        payload.getFormInstanceId(),
        payload.getRenderJobId(),
        payload.getObjectKey(),
        bytes(payload.getPdfSha256()),
        e.actorId() == null ? null : e.actorId().value());
  }

  private static byte[] bytes(ByteBuffer buffer) {
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

  public InboxEndpoint decidedEndpoint() {
    return decided;
  }

  public InboxEndpoint generatedEndpoint() {
    return generated;
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
