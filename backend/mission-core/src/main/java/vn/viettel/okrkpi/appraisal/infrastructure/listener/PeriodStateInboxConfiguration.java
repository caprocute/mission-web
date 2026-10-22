package vn.viettel.okrkpi.appraisal.infrastructure.listener;

import java.lang.reflect.Method;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.vtokr.config.v1.KyDaHuyKhoa;
import vn.vtokr.config.v1.KyDaKhoa;
import vn.vtokr.oversight.v1.KyDaMoLaiHauKiem;

/**
 * CORE-04-01: ba consumer {@link PeriodStateListener} cho {@code okr}, {@code task}, {@code
 * product}, mỗi consumer một nhóm Kafka đọc EVT-09, EVT-22, EVT-26 và ghi qua nguồn dữ liệu, giao
 * dịch và inbox của schema đích. Tắt bằng {@code mission.appraisal.period-state.listener.enabled=
 * false}; khởi động theo {@code okrkpi.inbox.listener-auto-startup}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema")
@ConditionalOnProperty(
    prefix = "mission.appraisal.period-state.listener",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PeriodStateInboxConfiguration {

  @Bean
  ConcurrentMessageListenerContainer<String, byte[]> okrPeriodStateContainer(
      @Qualifier("okrDataSource") DataSource dataSource,
      @Qualifier("okrTransactionManager") PlatformTransactionManager tm,
      Inbox inbox) {
    return inbox.container("okr", dataSource, tm);
  }

  @Bean
  ConcurrentMessageListenerContainer<String, byte[]> taskPeriodStateContainer(
      @Qualifier("taskDataSource") DataSource dataSource,
      @Qualifier("taskTransactionManager") PlatformTransactionManager tm,
      Inbox inbox) {
    return inbox.container("task", dataSource, tm);
  }

  @Bean
  ConcurrentMessageListenerContainer<String, byte[]> productPeriodStateContainer(
      @Qualifier("productDataSource") DataSource dataSource,
      @Qualifier("productTransactionManager") PlatformTransactionManager tm,
      Inbox inbox) {
    return inbox.container("product", dataSource, tm);
  }

  @Bean
  Inbox periodStateInbox(
      InboxProperties properties,
      InboxPayloadDeserializer deserializer,
      InboxRetryDlqPublisher retryDlqPublisher,
      InboxMetrics metrics,
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
      @Value("${okrkpi.inbox.listener-auto-startup:true}") boolean autoStartup) {
    return new Inbox(
        properties, deserializer, retryDlqPublisher, metrics, bootstrapServers, autoStartup);
  }

  /** Điểm nhận của ba topic, theo tên topic, cho một {@link PeriodStateListener}. */
  public static Map<String, InboxEndpoint> endpoints(PeriodStateListener listener) {
    Map<String, InboxEndpoint> endpoints = new HashMap<>();
    endpoints.put(
        EventTopics.EVT_09_KY_DA_KHOA,
        endpoint(EventTopics.EVT_09_KY_DA_KHOA, listener, "onLocked", KyDaKhoa.class));
    endpoints.put(
        EventTopics.EVT_26_KY_DA_HUY_KHOA,
        endpoint(EventTopics.EVT_26_KY_DA_HUY_KHOA, listener, "onUnlocked", KyDaHuyKhoa.class));
    endpoints.put(
        EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM,
        endpoint(
            EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM,
            listener,
            "onReopened",
            KyDaMoLaiHauKiem.class));
    return endpoints;
  }

  private static InboxEndpoint endpoint(
      String topic, PeriodStateListener listener, String method, Class<?> payload) {
    try {
      Method m = PeriodStateListener.class.getMethod(method, payload, InboxEnvelope.class);
      // Không theo dõi phiên bản thực thể gốc: ba topic không chung thứ tự, listener tự so thời
      // điểm.
      return new InboxEndpoint(topic, listener.consumer(), "", listener, m);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Dựng container Kafka cho một schema; giữ phụ thuộc inbox chung của ba schema. */
  public record Inbox(
      InboxProperties properties,
      InboxPayloadDeserializer deserializer,
      InboxRetryDlqPublisher retryDlqPublisher,
      InboxMetrics metrics,
      String bootstrapServers,
      boolean autoStartup) {

    ConcurrentMessageListenerContainer<String, byte[]> container(
        String schema, DataSource dataSource, PlatformTransactionManager tm) {
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      PeriodStateListener listener = new PeriodStateListener(schema, jdbc);
      InboxMessageProcessor processor =
          new InboxMessageProcessor(
              jdbc,
              tm,
              schema,
              deserializer,
              retryDlqPublisher,
              metrics,
              properties == null ? null : properties.retry(),
              Clock.systemUtc());
      Map<String, InboxEndpoint> endpoints = endpoints(listener);
      Map<String, Object> props = new HashMap<>();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
      props.put(ConsumerConfig.GROUP_ID_CONFIG, "mission-core-" + listener.consumer());
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
      ContainerProperties container =
          new ContainerProperties(List.copyOf(endpoints.keySet()).toArray(String[]::new));
      container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
      container.setMessageListener(
          (AcknowledgingMessageListener<String, byte[]>)
              (record, ack) -> processor.process(endpoints.get(record.topic()), record, ack));
      ConcurrentMessageListenerContainer<String, byte[]> c =
          new ConcurrentMessageListenerContainer<>(
              new DefaultKafkaConsumerFactory<>(props), container);
      c.setAutoStartup(autoStartup);
      return c;
    }
  }
}
