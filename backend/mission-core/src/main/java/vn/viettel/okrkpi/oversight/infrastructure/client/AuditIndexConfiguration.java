package vn.viettel.okrkpi.oversight.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.SSLContext;
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
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.oversight.application.AccessLogSearchService;
import vn.viettel.okrkpi.oversight.application.AuditIndexingService;
import vn.viettel.okrkpi.oversight.infrastructure.listener.AuditIndexerListener;
import vn.viettel.okrkpi.oversight.infrastructure.persistence.OutboxOversightAudit;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * Chỉ mục kiểm toán và tra cứu nhật ký truy cập C2 (CORE-07-12). Chỉ bật khi có {@code
 * mission.oversight.audit-index.url}; hai thể hiện client dùng hai tài khoản tách biệt: {@code
 * reader} cho API (chỉ đọc), {@code writer} cho consumer {@code oversight.audit-indexer}. Mật khẩu
 * lấy từ biến môi trường do ESO cấp, không có giá trị mặc định.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mission.oversight.audit-index", name = "url")
public class AuditIndexConfiguration {

  @Bean(destroyMethod = "close")
  OpenSearchAuditIndex auditIndexReader(
      @Value("${mission.oversight.audit-index.url}") URI url,
      @Value("${mission.oversight.audit-index.reader.username:}") String username,
      @Value("${mission.oversight.audit-index.reader.password:}") String password,
      @Value("${mission.oversight.audit-index.pooled-tenants:}") List<UUID> pooled,
      @Qualifier("auditIndexSslContext") ObjectProvider<SSLContext> ssl,
      ObjectProvider<ObjectMapper> json) {
    return new OpenSearchAuditIndex(
        url,
        username,
        password,
        ssl.getIfAvailable(),
        set(pooled),
        json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  AccessLogSearchService accessLogSearchService(
      @Qualifier("auditIndexReader") OpenSearchAuditIndex reader,
      @Qualifier("oversightDataSource") DataSource dataSource,
      @Value("${mission.oversight.outbox.registry-url:${okrkpi.inbox.registry.url:}}")
          String registry,
      ObjectProvider<OutboxPayloadSerializer> serializer,
      ObjectProvider<ObjectMapper> json) {
    OutboxWriter outbox =
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "oversight",
            serializer.getIfAvailable(() -> new ApicurioPayloadSerializer(registry, Map.of())),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc());
    return new AccessLogSearchService(reader, new OutboxOversightAudit(outbox));
  }

  private static Set<UUID> set(List<UUID> pooled) {
    return pooled == null ? Set.of() : new HashSet<>(pooled);
  }

  /**
   * Consumer {@code oversight.audit-indexer}: {@code sys.audit.v1} qua inbox của schema oversight
   * vào chỉ mục. Tắt bằng {@code mission.oversight.audit-indexer.enabled=false}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "mission.oversight.audit-index", name = "url")
  @ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema")
  @ConditionalOnProperty(
      prefix = "mission.oversight.audit-indexer",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class Indexer {

    @Bean(destroyMethod = "close")
    OpenSearchAuditIndex auditIndexWriter(
        @Value("${mission.oversight.audit-index.url}") URI url,
        @Value("${mission.oversight.audit-index.writer.username:}") String username,
        @Value("${mission.oversight.audit-index.writer.password:}") String password,
        @Value("${mission.oversight.audit-index.pooled-tenants:}") List<UUID> pooled,
        @Qualifier("auditIndexSslContext") ObjectProvider<SSLContext> ssl,
        ObjectProvider<ObjectMapper> json) {
      return new OpenSearchAuditIndex(
          url,
          username,
          password,
          ssl.getIfAvailable(),
          set(pooled),
          json.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    AuditIndexerListener auditIndexerListener(
        @Qualifier("auditIndexWriter") OpenSearchAuditIndex writer) {
      return new AuditIndexerListener(new AuditIndexingService(writer));
    }

    @Bean
    ConcurrentMessageListenerContainer<String, byte[]> auditIndexerContainer(
        AuditIndexerListener listener,
        @Qualifier("oversightDataSource") DataSource dataSource,
        @Qualifier("oversightTransactionManager") PlatformTransactionManager tm,
        InboxProperties properties,
        InboxPayloadDeserializer deserializer,
        InboxRetryDlqPublisher retryDlqPublisher,
        InboxMetrics metrics,
        @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
        @Value(
                "${mission.oversight.audit-indexer.auto-startup:${okrkpi.inbox.listener-auto-startup:true}}")
            boolean autoStartup) {
      InboxMessageProcessor processor =
          new InboxMessageProcessor(
              new JdbcTemplate(dataSource),
              tm,
              "oversight",
              deserializer,
              retryDlqPublisher,
              metrics,
              properties == null ? null : properties.retry(),
              Clock.systemUtc());
      InboxEndpoint endpoint = endpoint(listener);
      Map<String, Object> props = new HashMap<>();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
      props.put(ConsumerConfig.GROUP_ID_CONFIG, "mission-core-" + AuditIndexerListener.CONSUMER);
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
      ContainerProperties container = new ContainerProperties(EventTopics.SYS_AUDIT_V1);
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

  /** Điểm nhận {@code sys.audit.v1} của {@link AuditIndexerListener}; không kiểm phiên bản. */
  public static InboxEndpoint endpoint(AuditIndexerListener listener) {
    try {
      return new InboxEndpoint(
          EventTopics.SYS_AUDIT_V1,
          AuditIndexerListener.CONSUMER,
          null,
          listener,
          AuditIndexerListener.class.getMethod("on", AuditEvent.class, InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }
}
