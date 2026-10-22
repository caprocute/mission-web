package vn.viettel.okrkpi.task.infrastructure.listener;

import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListenerRegistrar;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.task.application.direct.TaskDirectionService;
import vn.vtokr.org.v1.PhanCongDaThayDoi;

/**
 * Consumer EVT-15 {@code org.assignment.changed.v1} của MOD-02 (CORE-02-25): phân công của người
 * chủ trì kết thúc thì đánh dấu nhiệm vụ cần giao lại và báo người giao việc. Inbox riêng ghi
 * {@code task.inbox_processed} (tách khỏi consumer cập nhật {@code org_replica}), nên nhận trùng sự
 * kiện không báo lại; cách đăng ký như {@link PlanSignatureConsumer}.
 */
public class LeadMovedConsumer implements SmartLifecycle {

  static final String CONSUMER = "task.lead-moved";

  private final TaskDirectionService directions;
  private final InboxMessageProcessor processor;
  private final InboxEndpoint endpoint;
  private final InboxListenerRegistrar registrar;

  public LeadMovedConsumer(
      TaskDirectionService directions,
      InboxMessageProcessor processor,
      ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>> containers,
      boolean autoStartup) {
    this.directions = Objects.requireNonNull(directions, "directions");
    this.processor = Objects.requireNonNull(processor, "processor");
    try {
      this.endpoint =
          new InboxEndpoint(
              EventTopics.EVT_15_PHAN_CONG_DA_THAY_DOI,
              CONSUMER,
              "",
              this,
              LeadMovedConsumer.class.getMethod("onAssignment", InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
    this.registrar = new InboxListenerRegistrar(processor, containers, autoStartup);
    registrar.registerEndpoint(endpoint);
  }

  /** Áp một EVT-15; phân công chưa có ngày kết thúc thì bỏ qua. */
  public void onAssignment(InboxEnvelope<PhanCongDaThayDoi> e) {
    PhanCongDaThayDoi a = e.payload();
    if (a.getValidTo() == null) {
      return;
    }
    directions.onAssignmentEnded(
        e.tenantId(), a.getPersonId(), a.getAssignmentId(), a.getUnitId(), a.getValidTo());
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
