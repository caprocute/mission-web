package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.oversight.api.AnomalyAlertQueryApi;
import vn.viettel.okrkpi.oversight.application.alert.AlertCaseService;
import vn.viettel.okrkpi.oversight.application.alert.AnomalyAlertIntake;
import vn.viettel.okrkpi.oversight.application.alert.AnomalyDigest;
import vn.viettel.okrkpi.oversight.application.port.AlertHandlers;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService;
import vn.viettel.okrkpi.oversight.infrastructure.client.ModuleAlertHandlers;
import vn.viettel.okrkpi.oversight.infrastructure.client.ModuleAlertSubjects;
import vn.viettel.okrkpi.oversight.infrastructure.listener.AnomalyDetectedListener;
import vn.viettel.okrkpi.oversight.infrastructure.scheduling.AnomalyDigestJob;
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
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.vtokr.analytics.v1.DauHieuBatThuong;

/**
 * Cảnh báo bất thường (CORE-07-10): kho JDBC trên {@code oversightDataSource}, dịch vụ tiếp nhận
 * EVT-20, cổng đọc {@link AnomalyAlertQueryApi}, consumer EVT-20 và job thông báo tổng hợp; dịch vụ
 * xử lý cảnh báo của trung tâm cảnh báo (CORE-07-11).
 */
@Configuration(proxyBeanMethods = false)
public class AnomalyAlertConfiguration {

  @Bean
  JdbcAnomalyAlertRepository anomalyAlertRepository(
      @Qualifier("oversightDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcAnomalyAlertRepository(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  AlertHandlers anomalyAlertHandlers(
      ObjectProvider<ProfileParticipantsApi> participants,
      ObjectProvider<AccessDirectory> directory) {
    return new ModuleAlertHandlers(participants.getIfAvailable(), directory.getIfAvailable());
  }

  @Bean
  AnomalyAlertIntake anomalyAlertIntake(
      JdbcAnomalyAlertRepository anomalyAlertRepository,
      PeriodApi periods,
      AlertHandlers anomalyAlertHandlers,
      ObjectProvider<TenantSettingApi> settings) {
    return new AnomalyAlertIntake(
        anomalyAlertRepository,
        periods,
        anomalyAlertHandlers,
        settings.getIfAvailable(),
        java.time.Clock.systemUTC());
  }

  @Bean
  AnomalyDigest anomalyDigest(
      JdbcAnomalyAlertRepository anomalyAlertRepository,
      AlertHandlers anomalyAlertHandlers,
      @Qualifier("oversightDataSource") DataSource dataSource,
      @Value("${mission.oversight.outbox.registry-url:${okrkpi.inbox.registry.url:}}")
          String registry,
      ObjectProvider<OutboxPayloadSerializer> serializer,
      ObjectProvider<ObjectMapper> json) {
    OutboxWriter outbox = outbox(dataSource, registry, serializer, json);
    return new AnomalyDigest(
        anomalyAlertRepository,
        anomalyAlertHandlers,
        new OutboxAlertNotices(outbox),
        java.time.Clock.systemUTC());
  }

  @Bean
  AlertCaseService alertCaseService(
      JdbcAnomalyAlertRepository anomalyAlertRepository,
      AlertHandlers anomalyAlertHandlers,
      PeriodApi periods,
      ObjectProvider<ProfileParticipantsApi> participants,
      ObjectProvider<AccessDirectory> directory,
      ObjectProvider<MeterRegistry> meters,
      ObjectProvider<ReopenRequestService> reopens,
      @Qualifier("oversightDataSource") DataSource dataSource,
      @Value("${mission.oversight.outbox.registry-url:${okrkpi.inbox.registry.url:}}")
          String registry,
      ObjectProvider<OutboxPayloadSerializer> serializer,
      ObjectProvider<ObjectMapper> json) {
    OutboxWriter outbox = outbox(dataSource, registry, serializer, json);
    MeterRegistry registryBean = meters.getIfAvailable();
    AlertCaseService service =
        new AlertCaseService(
            anomalyAlertRepository,
            new ModuleAlertSubjects(participants.getIfAvailable(), directory.getIfAvailable()),
            anomalyAlertHandlers,
            periods,
            new OutboxAlertNotices(outbox),
            new OutboxOversightAudit(outbox),
            registryBean == null ? null : new MicrometerAlertMetrics(registryBean),
            java.time.Clock.systemUTC());
    reopens.ifAvailable(r -> r.setAlertCases(service));
    return service;
  }

  @Bean
  AnomalyAlertQueryApi anomalyAlertQueryApi(
      JdbcAnomalyAlertRepository anomalyAlertRepository,
      @Qualifier("oversightTransactionManager") PlatformTransactionManager tm) {
    return new TransactionalAnomalyAlertQueries(anomalyAlertRepository, tm);
  }

  @Bean
  AnomalyDetectedListener anomalyDetectedListener(
      AnomalyAlertIntake anomalyAlertIntake, ObjectProvider<ObjectMapper> json) {
    return new AnomalyDetectedListener(anomalyAlertIntake, json.getIfAvailable(ObjectMapper::new));
  }

  private static OutboxWriter outbox(
      DataSource dataSource,
      String registry,
      ObjectProvider<OutboxPayloadSerializer> serializer,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxWriter(
        new JdbcTemplate(dataSource),
        "oversight",
        serializer.getIfAvailable(() -> new ApicurioPayloadSerializer(registry, Map.of())),
        json.getIfAvailable(ObjectMapper::new),
        Clock.systemUtc());
  }

  /** Điểm nhận EVT-20; không kiểm phiên bản aggregate (sự kiện không đi qua outbox PostgreSQL). */
  public static InboxEndpoint anomalyEndpoint(AnomalyDetectedListener listener) {
    try {
      return new InboxEndpoint(
          EventTopics.EVT_20_DAU_HIEU_BAT_THUONG,
          AnomalyDetectedListener.CONSUMER,
          null,
          listener,
          AnomalyDetectedListener.class.getMethod(
              "on", DauHieuBatThuong.class, InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Bộ xử lý inbox trên {@code oversight.inbox_processed}. */
  public static InboxMessageProcessor oversightInboxProcessor(
      DataSource dataSource,
      PlatformTransactionManager tm,
      InboxProperties properties,
      InboxPayloadDeserializer deserializer,
      InboxRetryDlqPublisher retryDlqPublisher,
      InboxMetrics metrics) {
    return new InboxMessageProcessor(
        new JdbcTemplate(dataSource),
        tm,
        "oversight",
        deserializer,
        retryDlqPublisher,
        metrics,
        properties == null ? null : properties.retry(),
        Clock.systemUtc());
  }

  /**
   * Consumer EVT-20; cần {@code okrkpi.inbox.schema}, tắt bằng {@code
   * mission.oversight.anomaly-listener.enabled=false}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema")
  @ConditionalOnProperty(
      prefix = "mission.oversight.anomaly-listener",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class AnomalyInbox {

    @Bean
    ConcurrentMessageListenerContainer<String, byte[]> anomalyDetectedContainer(
        AnomalyDetectedListener listener,
        @Qualifier("oversightDataSource") DataSource dataSource,
        @Qualifier("oversightTransactionManager") PlatformTransactionManager tm,
        InboxProperties properties,
        InboxPayloadDeserializer deserializer,
        InboxRetryDlqPublisher retryDlqPublisher,
        InboxMetrics metrics,
        @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
        @Value("${okrkpi.inbox.listener-auto-startup:true}") boolean autoStartup) {
      InboxMessageProcessor processor =
          oversightInboxProcessor(
              dataSource, tm, properties, deserializer, retryDlqPublisher, metrics);
      InboxEndpoint endpoint = anomalyEndpoint(listener);
      Map<String, Object> props = new HashMap<>();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
      props.put(ConsumerConfig.GROUP_ID_CONFIG, "mission-core-" + AnomalyDetectedListener.CONSUMER);
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
      ContainerProperties container =
          new ContainerProperties(EventTopics.EVT_20_DAU_HIEU_BAT_THUONG);
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

  /** Thông báo tổng hợp; tắt bằng {@code mission.oversight.anomaly-digest.enabled=false}. */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "mission.oversight.anomaly-digest",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class DigestJob {

    @Bean
    AnomalyDigestJob anomalyDigestJob(
        AnomalyDigest anomalyDigest,
        @Qualifier("oversightTransactionManager") PlatformTransactionManager tm,
        ObjectProvider<TenantDirectory> directory,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
      return new AnomalyDigestJob(anomalyDigest, tm, directory, cell, java.time.Clock.systemUTC());
    }
  }
}
