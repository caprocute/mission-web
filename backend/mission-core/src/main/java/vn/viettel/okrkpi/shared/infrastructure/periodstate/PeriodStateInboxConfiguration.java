package vn.viettel.okrkpi.shared.infrastructure.periodstate;

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
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.vtokr.config.v1.KyDaHuyKhoa;
import vn.vtokr.config.v1.KyDaKhoa;
import vn.vtokr.oversight.v1.KyDaMoLaiHauKiem;

/**
 * Consumer EVT-09, EVT-22, EVT-26 cập nhật {@code period_state} của {@code okr}, {@code task},
 * {@code product} (CORE-04-01). Mỗi schema một container, một consumer group và inbox của chính
 * schema đó, vì vai trò {@code svc_<schema>} chỉ ghi được schema của mình. Tắt bằng {@code
 * mission.period-state.listener.enabled=false}; khởi động theo {@code
 * okrkpi.inbox.listener-auto-startup}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema")
@ConditionalOnProperty(
    prefix = "mission.period-state.listener",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PeriodStateInboxConfiguration {

  /** Tiền tố định danh consumer trong {@code <schema>.inbox_processed}. */
  public static final String CONSUMER_PREFIX = "period-state.";

  private static final List<String> TOPICS =
      List.of(
          EventTopics.EVT_09_KY_DA_KHOA,
          EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM,
          EventTopics.EVT_26_KY_DA_HUY_KHOA);

  @Bean
  ConcurrentMessageListenerContainer<String, byte[]> okrPeriodStateContainer(
      @Qualifier("okrDataSource") DataSource dataSource,
      @Qualifier("okrTransactionManager") PlatformTransactionManager tm,
      Shared shared) {
    return container("okr", dataSource, tm, shared);
  }

  @Bean
  ConcurrentMessageListenerContainer<String, byte[]> taskPeriodStateContainer(
      @Qualifier("taskDataSource") DataSource dataSource,
      @Qualifier("taskTransactionManager") PlatformTransactionManager tm,
      Shared shared) {
    return container("task", dataSource, tm, shared);
  }

  @Bean
  ConcurrentMessageListenerContainer<String, byte[]> productPeriodStateContainer(
      @Qualifier("productDataSource") DataSource dataSource,
      @Qualifier("productTransactionManager") PlatformTransactionManager tm,
      Shared shared) {
    return container("product", dataSource, tm, shared);
  }

  @Bean
  Shared periodStateInboxShared(
      InboxProperties properties,
      InboxPayloadDeserializer deserializer,
      InboxRetryDlqPublisher retryDlqPublisher,
      InboxMetrics metrics,
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
      @Value("${okrkpi.inbox.listener-auto-startup:true}") boolean autoStartup) {
    return new Shared(
        properties, deserializer, retryDlqPublisher, metrics, bootstrapServers, autoStartup);
  }

  /** Thành phần inbox dùng chung cho ba container. */
  record Shared(
      InboxProperties properties,
      InboxPayloadDeserializer deserializer,
      InboxRetryDlqPublisher retryDlqPublisher,
      InboxMetrics metrics,
      String bootstrapServers,
      boolean autoStartup) {}

  static ConcurrentMessageListenerContainer<String, byte[]> container(
      String schema, DataSource dataSource, PlatformTransactionManager tm, Shared shared) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    InboxMessageProcessor processor =
        new InboxMessageProcessor(
            jdbc,
            tm,
            schema,
            shared.deserializer(),
            shared.retryDlqPublisher(),
            shared.metrics(),
            shared.properties() == null ? null : shared.properties().retry(),
            Clock.systemUtc());
    Map<String, InboxEndpoint> endpoints =
        endpoints(new PeriodStateProjection(jdbc, schema), schema);
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, shared.bootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "mission-core-" + CONSUMER_PREFIX + schema);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    ContainerProperties container = new ContainerProperties(TOPICS.toArray(String[]::new));
    container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    container.setMessageListener(
        (AcknowledgingMessageListener<String, byte[]>)
            (record, ack) -> processor.process(endpoints.get(record.topic()), record, ack));
    ConcurrentMessageListenerContainer<String, byte[]> c =
        new ConcurrentMessageListenerContainer<>(
            new DefaultKafkaConsumerFactory<>(props), container);
    c.setAutoStartup(shared.autoStartup());
    return c;
  }

  /** Điểm nhận theo topic; không kiểm phiên bản aggregate (thứ tự xét bằng thời điểm sự kiện). */
  static Map<String, InboxEndpoint> endpoints(PeriodStateProjection projection, String schema) {
    String consumer = CONSUMER_PREFIX + schema;
    try {
      return Map.of(
          EventTopics.EVT_09_KY_DA_KHOA,
          new InboxEndpoint(
              EventTopics.EVT_09_KY_DA_KHOA,
              consumer,
              null,
              projection,
              PeriodStateProjection.class.getMethod(
                  "onLocked", KyDaKhoa.class, InboxEnvelope.class)),
          EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM,
          new InboxEndpoint(
              EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM,
              consumer,
              null,
              projection,
              PeriodStateProjection.class.getMethod(
                  "onReopened", KyDaMoLaiHauKiem.class, InboxEnvelope.class)),
          EventTopics.EVT_26_KY_DA_HUY_KHOA,
          new InboxEndpoint(
              EventTopics.EVT_26_KY_DA_HUY_KHOA,
              consumer,
              null,
              projection,
              PeriodStateProjection.class.getMethod(
                  "onUnlocked", KyDaHuyKhoa.class, InboxEnvelope.class)));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }
}
