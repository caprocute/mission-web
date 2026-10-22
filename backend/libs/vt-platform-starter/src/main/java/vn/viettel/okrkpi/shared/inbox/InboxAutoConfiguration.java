package vn.viettel.okrkpi.shared.inbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.shared.kernel.Clock;

/**
 * NEN-012 · ADR-03 · Auto-configuration cho hạ tầng tiêu thụ sự kiện idempotent ({@code
 * okrkpi.inbox.*}). Chỉ kích hoạt khi mô-đun hoặc service khai {@code okrkpi.inbox.schema}.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
      "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
      "vn.viettel.okrkpi.shared.tenant.TenantAutoConfiguration",
      "vn.viettel.okrkpi.shared.outbox.OutboxAutoConfiguration"
    })
@ConditionalOnClass({JdbcTemplate.class, KafkaTemplate.class})
@ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema")
@EnableConfigurationProperties(InboxProperties.class)
public class InboxAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public InboxMetrics inboxMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
    return new InboxMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
  }

  @Bean
  @ConditionalOnMissingBean
  public InboxPayloadDeserializer inboxPayloadDeserializer(
      InboxProperties properties, Environment env) {
    String url = properties.registry().url();
    if (url == null || url.isBlank()) {
      url = env.getProperty("okrkpi.outbox.registry.url");
    }
    if (url == null || url.isBlank()) {
      throw new IllegalStateException(
          "Thiếu okrkpi.inbox.registry.url (hoặc okrkpi.outbox.registry.url): Inbox consumer cần"
              + " Apicurio Registry để giải tuần tự sự kiện");
    }
    return new ApicurioPayloadDeserializer(url, properties.registry().properties());
  }

  @Bean
  @ConditionalOnMissingBean(name = "inboxKafkaOperations")
  public KafkaOperations<String, byte[]> inboxKafkaOperations(
      ObjectProvider<KafkaProperties> kafkaProperties) {
    Map<String, Object> props =
        new HashMap<>(
            kafkaProperties.getIfAvailable(KafkaProperties::new).buildProducerProperties(null));
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
  }

  @Bean
  @ConditionalOnMissingBean
  public InboxRetryDlqPublisher inboxRetryDlqPublisher(
      KafkaOperations<String, byte[]> inboxKafkaOperations) {
    return new InboxRetryDlqPublisher(inboxKafkaOperations);
  }

  @Bean
  @ConditionalOnMissingBean(name = "inboxKafkaListenerContainerFactory")
  public ConcurrentKafkaListenerContainerFactory<String, byte[]> inboxKafkaListenerContainerFactory(
      ObjectProvider<KafkaProperties> kafkaProperties) {
    Map<String, Object> props =
        new HashMap<>(
            kafkaProperties.getIfAvailable(KafkaProperties::new).buildConsumerProperties(null));
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    return factory;
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(DataSource.class)
  public InboxMessageProcessor inboxMessageProcessor(
      DataSource dataSource,
      ObjectProvider<PlatformTransactionManager> transactionManager,
      InboxProperties properties,
      InboxPayloadDeserializer deserializer,
      InboxRetryDlqPublisher retryDlqPublisher,
      InboxMetrics metrics,
      ObjectProvider<Clock> clock) {
    PlatformTransactionManager txManager =
        transactionManager.getIfAvailable(() -> new DataSourceTransactionManager(dataSource));
    return new InboxMessageProcessor(
        new JdbcTemplate(dataSource),
        txManager,
        properties.schema(),
        deserializer,
        retryDlqPublisher,
        metrics,
        properties.retry(),
        clock.getIfAvailable(Clock::systemUtc));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(InboxMessageProcessor.class)
  public static InboxListenerRegistrar inboxListenerRegistrar(
      ObjectProvider<InboxMessageProcessor> processor,
      ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>>
          inboxKafkaListenerContainerFactory,
      InboxProperties properties) {
    return new InboxListenerRegistrar(
        processor, inboxKafkaListenerContainerFactory, properties.listenerAutoStartup());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(DataSource.class)
  @ConditionalOnProperty(
      prefix = "okrkpi.inbox.purge",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public InboxPurgeJob inboxPurgeJob(
      DataSource dataSource,
      ObjectProvider<PlatformTransactionManager> transactionManager,
      InboxProperties properties) {
    PlatformTransactionManager txManager =
        transactionManager.getIfAvailable(() -> new DataSourceTransactionManager(dataSource));
    return new InboxPurgeJob(
        new JdbcTemplate(dataSource), txManager, properties.schema(), properties.purge());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(InboxListenerRegistrar.class)
  public InboxHealthIndicator inboxHealthIndicator(
      InboxProperties properties, InboxListenerRegistrar registrar) {
    return new InboxHealthIndicator(properties.schema(), registrar);
  }
}
