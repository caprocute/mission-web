package vn.viettel.okrkpi.task.infrastructure.listener;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListenerRegistrar;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.viettel.okrkpi.task.application.plan.PlanApprovalService;
import vn.vtokr.esign.v1.KySoDaHoanTat;
import vn.vtokr.esign.v1.SignedItem;

/**
 * Consumer EVT-18 {@code esign.signature.completed.v1} của MOD-02 (CORE-02-09): chữ ký của nguồn
 * {@code task} chuyển phiên bản kế hoạch sang {@code DA_DUYET}. Inbox ghi {@code
 * task.inbox_processed} bằng {@code taskDataSource}, khác inbox {@code org_replica} của bản sao tổ
 * chức, nên consumer tự giữ bộ xử lý inbox và bộ đăng ký riêng (không khai {@code @InboxListener}
 * để bộ đăng ký chung không nhận nhầm). Trùng sự kiện do inbox chặn; sự kiện lặp sau khi đã duyệt
 * cũng không đổi gì vì dịch vụ kiểm trạng thái.
 */
public class PlanSignatureConsumer implements SmartLifecycle {

  static final String CONSUMER = "task.plan-approval-signature";
  static final String SOURCE_MODULE = "task";

  private final PlanApprovalService approvals;
  private final InboxMessageProcessor processor;
  private final InboxEndpoint endpoint;
  private final InboxListenerRegistrar registrar;

  public PlanSignatureConsumer(
      PlanApprovalService approvals,
      InboxMessageProcessor processor,
      ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>> containers,
      boolean autoStartup) {
    this.approvals = Objects.requireNonNull(approvals, "approvals");
    this.processor = Objects.requireNonNull(processor, "processor");
    try {
      this.endpoint =
          new InboxEndpoint(
              EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
              CONSUMER,
              "",
              this,
              PlanSignatureConsumer.class.getMethod("onSigned", InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
    this.registrar = new InboxListenerRegistrar(processor, containers, autoStartup);
    registrar.registerEndpoint(endpoint);
  }

  /** Áp một EVT-18; nguồn khác {@code task} không thuộc MOD-02 và được bỏ qua. */
  public void onSigned(InboxEnvelope<KySoDaHoanTat> e) {
    KySoDaHoanTat payload = e.payload();
    if (!SOURCE_MODULE.equals(payload.getSourceModule())) {
      return;
    }
    if (e.actorId() == null) {
      throw new PermanentInboxException("EVT-18 thiếu người ký (actorid)");
    }
    List<byte[]> hashes =
        payload.getItems().stream().map(SignedItem::getDocumentSha256).map(this::bytes).toList();
    approvals.onSigned(
        e.tenantId(),
        new PlanApprovalService.Signed(
            payload.getSignRequestId(),
            payload.getSourceRefId(),
            hashes,
            e.actorId().value(),
            payload.getSignedAt()));
  }

  private byte[] bytes(ByteBuffer buffer) {
    ByteBuffer copy = buffer.duplicate();
    byte[] out = new byte[copy.remaining()];
    copy.get(out);
    return out;
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
