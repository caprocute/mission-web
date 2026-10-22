package vn.viettel.okrkpi.product.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.product.application.evidence.EvidenceLifecycleService;
import vn.viettel.okrkpi.product.application.evidence.EvidenceObjectProjector;
import vn.viettel.okrkpi.product.application.evidence.EvidenceSubmissionService;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceEvents;
import vn.viettel.okrkpi.product.infrastructure.listener.EvidenceObjectListener;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.vtokr.evidence.v1.ObjectScanned;
import vn.vtokr.sys.v1.EvidenceObjectStored;

/**
 * CORE-03-03: khai báo sản phẩm, nộp minh chứng, outbox {@code product.outbox_event} và consumer
 * bản chiếu đối tượng SVC-08 qua inbox của schema product.
 */
@Configuration(proxyBeanMethods = false)
public class EvidenceSubmissionConfiguration {

  @Bean
  JdbcEvidenceSubmissionRepository evidenceSubmissionRepository(
      @Qualifier("productDataSource") DataSource dataSource) {
    return new JdbcEvidenceSubmissionRepository(JdbcClient.create(dataSource));
  }

  @Bean
  EvidenceEvents evidenceEvents(
      @Qualifier("productDataSource") DataSource dataSource,
      @Value("${mission.product.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxEvidenceEvents(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "product",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  EvidenceSubmissionService evidenceSubmissionService(
      JdbcEvidenceSubmissionRepository evidenceSubmissionRepository,
      EvidenceLifecycleService evidenceLifecycleService,
      PeriodApi periods,
      TaskApi tasks,
      EvidenceEvents evidenceEvents) {
    return new EvidenceSubmissionService(
        evidenceSubmissionRepository,
        evidenceLifecycleService,
        periods,
        tasks,
        evidenceEvents,
        java.time.Clock.systemUTC());
  }

  @Bean
  EvidenceObjectProjector evidenceObjectProjector(
      JdbcEvidenceSubmissionRepository evidenceSubmissionRepository) {
    return new EvidenceObjectProjector(evidenceSubmissionRepository, java.time.Clock.systemUTC());
  }

  @Bean
  EvidenceObjectListener evidenceObjectListener(EvidenceObjectProjector evidenceObjectProjector) {
    return new EvidenceObjectListener(evidenceObjectProjector);
  }

  /**
   * Hai consumer bản chiếu đối tượng qua inbox của schema product. Tắt bằng {@code
   * mission.product.evidence-object.listener.enabled=false}; khởi động theo {@code
   * okrkpi.inbox.listener-auto-startup}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema")
  @ConditionalOnProperty(
      prefix = "mission.product.evidence-object.listener",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class ObjectInbox {

    @Bean
    ConcurrentMessageListenerContainer<String, byte[]> evidenceObjectStoredContainer(
        EvidenceObjectListener listener,
        @Qualifier("productDataSource") DataSource dataSource,
        @Qualifier("productTransactionManager") PlatformTransactionManager tm,
        InboxProperties properties,
        InboxPayloadDeserializer deserializer,
        InboxRetryDlqPublisher retryDlqPublisher,
        InboxMetrics metrics,
        @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
        @Value("${okrkpi.inbox.listener-auto-startup:true}") boolean autoStartup) {
      return container(
          productInboxProcessor(
              dataSource, tm, properties, deserializer, retryDlqPublisher, metrics),
          storedEndpoint(listener),
          bootstrapServers,
          autoStartup);
    }

    @Bean
    ConcurrentMessageListenerContainer<String, byte[]> evidenceObjectScannedContainer(
        EvidenceObjectListener listener,
        @Qualifier("productDataSource") DataSource dataSource,
        @Qualifier("productTransactionManager") PlatformTransactionManager tm,
        InboxProperties properties,
        InboxPayloadDeserializer deserializer,
        InboxRetryDlqPublisher retryDlqPublisher,
        InboxMetrics metrics,
        @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
        @Value("${okrkpi.inbox.listener-auto-startup:true}") boolean autoStartup) {
      return container(
          productInboxProcessor(
              dataSource, tm, properties, deserializer, retryDlqPublisher, metrics),
          scannedEndpoint(listener),
          bootstrapServers,
          autoStartup);
    }

    private static ConcurrentMessageListenerContainer<String, byte[]> container(
        InboxMessageProcessor processor,
        InboxEndpoint endpoint,
        String bootstrapServers,
        boolean autoStartup) {
      Map<String, Object> props = new HashMap<>();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
      props.put(ConsumerConfig.GROUP_ID_CONFIG, "mission-core-" + endpoint.consumer());
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
      ContainerProperties container = new ContainerProperties(endpoint.topic());
      container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
      container.setMessageListener(
          (AcknowledgingMessageListener<String, byte[]>)
              (record, ack) -> processor.process(endpoint, record, ack));
      ConcurrentMessageListenerContainer<String, byte[]> c =
          new ConcurrentMessageListenerContainer<>(
              new DefaultKafkaConsumerFactory<>(props), container);
      c.setAutoStartup(autoStartup);
      return c;
    }
  }

  /** Bộ xử lý inbox ghi {@code product.inbox_*} bằng nguồn dữ liệu và giao dịch của product. */
  public static InboxMessageProcessor productInboxProcessor(
      DataSource dataSource,
      PlatformTransactionManager tm,
      InboxProperties properties,
      InboxPayloadDeserializer deserializer,
      InboxRetryDlqPublisher retryDlqPublisher,
      InboxMetrics metrics) {
    return new InboxMessageProcessor(
        new JdbcTemplate(dataSource),
        tm,
        "product",
        deserializer,
        retryDlqPublisher,
        metrics,
        properties == null ? null : properties.retry(),
        Clock.systemUtc());
  }

  /**
   * Điểm nhận sự kiện lưu. Không khai loại thực thể gốc: khử trùng theo {@code event_id}, còn thứ
   * tự phiên bản đối tượng do bản chiếu kiểm (sự kiện quét cùng phiên bản đối tượng sẽ bị bộ đếm
   * phiên bản chung coi là cũ).
   */
  public static InboxEndpoint storedEndpoint(EvidenceObjectListener listener) {
    return endpoint(
        EventTopics.SYS_EVIDENCE_OBJECT_STORED_V1,
        EvidenceObjectListener.STORED_CONSUMER,
        listener,
        "onStored",
        EvidenceObjectStored.class);
  }

  /** Điểm nhận sự kiện quét. */
  public static InboxEndpoint scannedEndpoint(EvidenceObjectListener listener) {
    return endpoint(
        EventTopics.EVIDENCE_OBJECT_SCANNED_V1,
        EvidenceObjectListener.SCANNED_CONSUMER,
        listener,
        "onScanned",
        ObjectScanned.class);
  }

  private static InboxEndpoint endpoint(
      String topic,
      String consumer,
      EvidenceObjectListener listener,
      String method,
      Class<?> payload) {
    try {
      return new InboxEndpoint(
          topic,
          consumer,
          "",
          listener,
          EvidenceObjectListener.class.getMethod(method, payload, InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }
}
