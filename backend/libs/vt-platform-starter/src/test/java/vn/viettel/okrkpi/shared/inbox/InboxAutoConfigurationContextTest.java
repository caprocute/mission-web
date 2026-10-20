package vn.viettel.okrkpi.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.events.EventTopics;
import vn.vtokr.task.v1.TienDoDaCapNhat;

class InboxAutoConfigurationContextTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(KafkaAutoConfiguration.class, InboxAutoConfiguration.class))
          .withBean(DataSource.class, () -> mock(DataSource.class))
          .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
          .withBean(InboxPayloadDeserializer.class, () -> mock(InboxPayloadDeserializer.class))
          .withBean(DummyListener.class, DummyListener::new)
          .withPropertyValues(
              "okrkpi.inbox.schema=svc_eval",
              "okrkpi.inbox.listener-auto-startup=false",
              "spring.kafka.bootstrap-servers=localhost:9092");

  @Test
  void registersInboxBeansAndDiscoversInboxListenerEndpoints() {
    contextRunner.run(
        ctx -> {
          assertThat(ctx).hasSingleBean(InboxMessageProcessor.class);
          assertThat(ctx).hasSingleBean(InboxListenerRegistrar.class);
          assertThat(ctx).hasSingleBean(InboxPurgeJob.class);
          assertThat(ctx).hasSingleBean(InboxHealthIndicator.class);

          InboxListenerRegistrar registrar = ctx.getBean(InboxListenerRegistrar.class);
          assertThat(registrar.endpoints()).hasSize(1);
          assertThat(registrar.endpoints().get(0).consumer()).isEqualTo("svc-eval.dummy");
          assertThat(registrar.isAutoStartup()).isFalse();
        });
  }

  static class DummyListener {
    @InboxListener(
        topic = EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT,
        consumer = "svc-eval.dummy",
        aggregateType = "Task")
    public void handle(InboxEnvelope<TienDoDaCapNhat> envelope) {}
  }
}
