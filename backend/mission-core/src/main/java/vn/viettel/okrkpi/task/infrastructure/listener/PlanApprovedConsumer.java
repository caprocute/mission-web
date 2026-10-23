package vn.viettel.okrkpi.task.infrastructure.listener;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxListenerRegistrar;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.task.application.fromplan.PlanTaskGenerator;
import vn.vtokr.task.v1.KeHoachDaDuyet;

/**
 * Consumer EVT-03 {@code task.plan.approved.v1} của MOD-02 (CORE-02-11): sinh, cập nhật, huỷ nhiệm
 * vụ theo kế hoạch vừa duyệt. Inbox riêng ghi {@code task.inbox_processed}, nên nhận trùng không
 * sinh lại; cách đăng ký như {@link LeadMovedConsumer}. Chỉ số {@value #METRIC} đo từ lúc duyệt
 * (tiêu đề {@code occurredat}) tới khi xử lý xong.
 */
public class PlanApprovedConsumer implements SmartLifecycle {

  static final String CONSUMER = "task.plan-approved";
  static final String METRIC = "mission.task.plan.generate.latency";

  private final PlanTaskGenerator generator;
  private final InboxMessageProcessor processor;
  private final InboxEndpoint endpoint;
  private final InboxListenerRegistrar registrar;
  private final Timer latency;

  public PlanApprovedConsumer(
      PlanTaskGenerator generator,
      InboxMessageProcessor processor,
      ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>> containers,
      MeterRegistry meters,
      boolean autoStartup) {
    this.generator = Objects.requireNonNull(generator, "generator");
    this.processor = Objects.requireNonNull(processor, "processor");
    this.latency =
        meters == null
            ? null
            : Timer.builder(METRIC)
                .description("Thời gian từ khi duyệt kế hoạch tới khi sinh xong nhiệm vụ")
                .register(meters);
    try {
      this.endpoint =
          new InboxEndpoint(
              EventTopics.EVT_03_KE_HOACH_DA_DUYET,
              CONSUMER,
              "",
              this,
              PlanApprovedConsumer.class.getMethod("onApproved", InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
    this.registrar = new InboxListenerRegistrar(processor, containers, autoStartup);
    registrar.registerEndpoint(endpoint);
  }

  /** Áp một EVT-03. */
  public void onApproved(InboxEnvelope<KeHoachDaDuyet> e) {
    KeHoachDaDuyet p = e.payload();
    generator.onApproved(
        e.tenantId(),
        p.getPlanId(),
        p.getPlanVersionId(),
        p.getSupersededVersionId(),
        p.getApprovedBy());
    if (latency != null && e.occurredAt() != null) {
      Duration d = Duration.between(e.occurredAt(), Instant.now());
      latency.record(d.isNegative() ? Duration.ZERO : d);
    }
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
