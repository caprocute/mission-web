package vn.viettel.okrkpi.shared.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.vtokr.task.v1.TienDoDaCapNhat;

class InboxAutoConfigurationTest {

  private final InboxAutoConfiguration config = new InboxAutoConfiguration();

  @Test
  void createsDefaultBeansAndConfiguresKafkaReadCommittedAndIdempotentProducer() {
    InboxProperties props =
        new InboxProperties(
            "svc_task",
            new InboxProperties.Registry("http://localhost:8081/apis/registry/v3", Map.of()),
            new InboxProperties.Retry(
                3, Duration.ofMillis(10), 2.0, List.of(Duration.ofSeconds(1))),
            new InboxProperties.Purge(true, "7 days", 500, 5, "0 0 3 * * *"),
            false);

    KafkaProperties kafkaProperties = new KafkaProperties();
    kafkaProperties.setBootstrapServers(List.of("localhost:9092"));

    ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
        config.inboxKafkaListenerContainerFactory(providerOf(kafkaProperties));
    assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(AckMode.MANUAL_IMMEDIATE);
    assertThat(factory.getConsumerFactory().getConfigurationProperties())
        .containsEntry(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed")
        .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

    KafkaOperations<String, byte[]> ops = config.inboxKafkaOperations(providerOf(kafkaProperties));
    assertThat(ops).isInstanceOf(KafkaTemplate.class);
    KafkaTemplate<String, byte[]> kt = (KafkaTemplate<String, byte[]>) ops;
    assertThat(kt.getProducerFactory().getConfigurationProperties())
        .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
        .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

    InboxRetryDlqPublisher publisher = config.inboxRetryDlqPublisher(ops);
    assertThat(publisher).isNotNull();

    InboxMetrics metrics = config.inboxMetrics(providerOf(new SimpleMeterRegistry()));
    assertThat(metrics).isNotNull();

    InboxPayloadDeserializer deserializer =
        config.inboxPayloadDeserializer(props, new MockEnvironment());
    assertThat(deserializer).isInstanceOf(ApicurioPayloadDeserializer.class);
    ((ApicurioPayloadDeserializer) deserializer).close();

    DataSource ds = mock(DataSource.class);
    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    InboxMessageProcessor processor =
        config.inboxMessageProcessor(
            ds,
            providerOf(txManager),
            props,
            deserializer,
            publisher,
            metrics,
            providerOf(Clock.systemUtc()));
    assertThat(processor.schema()).isEqualTo("svc_task");

    InboxPurgeJob purgeJob = config.inboxPurgeJob(ds, providerOf(txManager), props);
    assertThat(purgeJob).isNotNull();

    InboxListenerRegistrar registrar =
        InboxAutoConfiguration.inboxListenerRegistrar(
            providerOf(processor), providerOf(factory), props);
    InboxHealthIndicator health = config.inboxHealthIndicator(props, registrar);
    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void rejectsMissingSchemaOrMissingRegistryUrl() {
    InboxProperties emptyProps = new InboxProperties(null, null, null, null, false);

    assertThatThrownBy(() -> config.inboxPayloadDeserializer(emptyProps, new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("okrkpi.inbox.registry.url");

    DataSource ds = mock(DataSource.class);
    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    assertThatThrownBy(() -> config.inboxPurgeJob(ds, providerOf(txManager), emptyProps))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("okrkpi.inbox.schema");
  }

  @Test
  void fallsBackToOutboxRegistryUrlWhenInboxRegistryOmitted() {
    InboxProperties inboxProps = new InboxProperties("svc_eval", null, null, null, false);
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("okrkpi.outbox.registry.url", "http://localhost:8081/apis/registry/v3");

    InboxPayloadDeserializer deserializer = config.inboxPayloadDeserializer(inboxProps, env);
    assertThat(deserializer).isInstanceOf(ApicurioPayloadDeserializer.class);
    ((ApicurioPayloadDeserializer) deserializer).close();
  }

  @Test
  void endpointInspectsInboxListenerMethodsAndRejectsInvalidSignatures() throws Exception {
    SampleListener sample = new SampleListener();
    var m1 = SampleListener.class.getDeclaredMethod("onEvent", InboxEnvelope.class);
    InboxEndpoint ep1 = InboxEndpoint.fromMethod(sample, m1, m1.getAnnotation(InboxListener.class));
    assertThat(ep1.topic()).isEqualTo(EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT);
    assertThat(ep1.consumer()).isEqualTo("svc-eval.test");
    assertThat(ep1.aggregateType()).isEqualTo("Task");

    var m2 = SampleListener.class.getDeclaredMethod("onPayloadOnly", TienDoDaCapNhat.class);
    InboxEndpoint ep2 = InboxEndpoint.fromMethod(sample, m2, m2.getAnnotation(InboxListener.class));
    assertThat(ep2.aggregateType()).isEmpty();

    var bad = SampleListener.class.getDeclaredMethod("invalidNoArgs");
    assertThatThrownBy(
            () -> InboxEndpoint.fromMethod(sample, bad, bad.getAnnotation(InboxListener.class)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static class SampleListener {
    @InboxListener(
        topic = EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT,
        consumer = "svc-eval.test",
        aggregateType = "Task")
    void onEvent(InboxEnvelope<TienDoDaCapNhat> envelope) {
      assertThat(envelope.eventId()).isNotEqualTo(new UUID(0L, 0L));
    }

    @InboxListener(topic = EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT, consumer = "svc-eval.payload")
    void onPayloadOnly(TienDoDaCapNhat payload) {
      assertThat(payload).isNotNull();
    }

    @InboxListener(topic = EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT, consumer = "svc-eval.bad")
    void invalidNoArgs() {}
  }

  private static <T> ObjectProvider<T> providerOf(T instance) {
    return new ObjectProvider<>() {
      @Override
      public T getObject(Object... args) {
        return instance;
      }

      @Override
      public T getIfAvailable() {
        return instance;
      }

      @Override
      public T getIfUnique() {
        return instance;
      }

      @Override
      public T getObject() {
        return instance;
      }
    };
  }
}
