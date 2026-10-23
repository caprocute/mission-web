package vn.viettel.okrkpi.dossier.infrastructure.listener;

import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import vn.viettel.okrkpi.dossier.application.specialcase.SpecialCaseService;
import vn.viettel.okrkpi.dossier.domain.specialcase.AssignmentFact;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListenerRegistrar;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.vtokr.org.v1.PhanCongDaThayDoi;

/**
 * Consumer EVT-15 {@code org.assignment.changed.v1} của MOD-06 (CORE-06-05): phân công đổi trong kỳ
 * hiện hành thì đối chiếu lại trường hợp đặc thù của người đó. Inbox riêng ghi {@code
 * dossier.inbox_processed} nên sự kiện trùng bị chặn; sự kiện lặp với mã khác cũng không tạo dòng
 * trùng vì mỗi (người, kỳ, mã) chỉ có một dòng.
 */
public class SpecialCaseAssignmentConsumer implements SmartLifecycle {

  static final String CONSUMER = "dossier.special-case-assignment";

  private final SpecialCaseService specialCases;
  private final InboxMessageProcessor processor;
  private final InboxEndpoint endpoint;
  private final InboxListenerRegistrar registrar;

  public SpecialCaseAssignmentConsumer(
      SpecialCaseService specialCases,
      InboxMessageProcessor processor,
      ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>> containers,
      boolean autoStartup) {
    this.specialCases = Objects.requireNonNull(specialCases, "specialCases");
    this.processor = Objects.requireNonNull(processor, "processor");
    try {
      this.endpoint =
          new InboxEndpoint(
              EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI,
              CONSUMER,
              "",
              this,
              SpecialCaseAssignmentConsumer.class.getMethod("onAssignment", InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
    this.registrar = new InboxListenerRegistrar(processor, containers, autoStartup);
    registrar.registerEndpoint(endpoint);
  }

  /** Áp một EVT-15. */
  public void onAssignment(InboxEnvelope<PhanCongDaThayDoi> e) {
    PhanCongDaThayDoi a = e.payload();
    specialCases.assignmentChanged(
        e.tenantId(),
        a.getPersonId(),
        new AssignmentFact(
            a.getAssignmentId(),
            a.getUnitId(),
            a.getKind().toString(),
            a.getIsHead(),
            a.getValidFrom(),
            a.getValidTo()));
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
