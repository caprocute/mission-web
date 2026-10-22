package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

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
import vn.viettel.okrkpi.appraisal.api.AppraisalSpecialCases;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.setup.ProfileSetupService;
import vn.viettel.okrkpi.appraisal.infrastructure.listener.ProfileSetupListener;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
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
import vn.vtokr.config.v1.KyDaKhoa;
import vn.vtokr.scoring.v1.DiemDaTinh;

/**
 * CORE-04-02: lập hồ sơ quý khi nhận EVT-09, lưu ảnh chụp điểm khi nhận EVT-10, cả hai qua inbox
 * của schema appraisal. Trường hợp đặc thù đọc qua {@link AppraisalSpecialCases} nếu MOD-06 đã khai
 * báo bean.
 */
@Configuration(proxyBeanMethods = false)
public class ProfileSetupConfiguration {

  /** Loại aggregate giữ thứ tự EVT-09 (kỳ) và EVT-10 (hồ sơ) theo {@code aggregate_version}. */
  static final String PERIOD_AGGREGATE = "period";

  static final String PROFILE_AGGREGATE = "APPRAISAL_PROFILE";

  @Bean
  JdbcProfileSetupRepository profileSetupRepository(
      @Qualifier("appraisalDataSource") DataSource dataSource) {
    return new JdbcProfileSetupRepository(JdbcClient.create(dataSource));
  }

  @Bean
  ProfileSetupService profileSetupService(
      AppraisalProfileService appraisalProfileService,
      JdbcProfileSetupRepository profileSetupRepository,
      @Qualifier("appraisalDataSource") DataSource dataSource,
      @Value("${mission.appraisal.outbox.registry-url:${okrkpi.inbox.registry.url}}")
          String registry,
      ObjectProvider<ObjectMapper> json,
      ObjectProvider<PolicyQueryApi> policies,
      ObjectProvider<AppraisalSpecialCases> specialCases,
      ObjectProvider<PeriodApi> periods) {
    OutboxWriter outbox =
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "appraisal",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc());
    return new ProfileSetupService(
        appraisalProfileService,
        profileSetupRepository,
        new OutboxSetupNotices(outbox),
        policies.getIfAvailable(),
        specialCases.getIfAvailable(),
        periods.getIfAvailable(),
        Clock.systemUtc());
  }

  /**
   * Consumer EVT-09, EVT-10. Tắt bằng {@code mission.appraisal.setup-listener.enabled=false}; khởi
   * động theo {@code okrkpi.inbox.listener-auto-startup}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema")
  @ConditionalOnProperty(
      prefix = "mission.appraisal.setup-listener",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class SetupInbox {

    @Bean
    ProfileSetupListener profileSetupListener(ProfileSetupService profileSetupService) {
      return new ProfileSetupListener(profileSetupService);
    }

    @Bean
    ConcurrentMessageListenerContainer<String, byte[]> profileSetupContainer(
        ProfileSetupListener listener,
        @Qualifier("appraisalDataSource") DataSource dataSource,
        @Qualifier("appraisalTransactionManager") PlatformTransactionManager tm,
        InboxProperties properties,
        InboxPayloadDeserializer deserializer,
        InboxRetryDlqPublisher retryDlqPublisher,
        InboxMetrics metrics,
        @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
        @Value("${okrkpi.inbox.listener-auto-startup:true}") boolean autoStartup) {
      InboxMessageProcessor processor =
          new InboxMessageProcessor(
              new JdbcTemplate(dataSource),
              tm,
              "appraisal",
              deserializer,
              retryDlqPublisher,
              metrics,
              properties == null ? null : properties.retry(),
              Clock.systemUtc());
      Map<String, InboxEndpoint> endpoints = endpoints(listener);
      Map<String, Object> props = new HashMap<>();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
      props.put(ConsumerConfig.GROUP_ID_CONFIG, "mission-core-appraisal.profile-setup");
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
      ContainerProperties container =
          new ContainerProperties(EventTopics.EVT_09_KY_DA_KHOA, EventTopics.EVT_10_DIEM_DA_TINH);
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

  /** Điểm nhận EVT-09 (thứ tự theo kỳ) và EVT-10 (thứ tự theo hồ sơ). */
  public static Map<String, InboxEndpoint> endpoints(ProfileSetupListener listener) {
    try {
      return Map.of(
          EventTopics.EVT_09_KY_DA_KHOA,
          new InboxEndpoint(
              EventTopics.EVT_09_KY_DA_KHOA,
              ProfileSetupListener.LOCKED_CONSUMER,
              PERIOD_AGGREGATE,
              listener,
              ProfileSetupListener.class.getMethod(
                  "onLocked", KyDaKhoa.class, InboxEnvelope.class)),
          EventTopics.EVT_10_DIEM_DA_TINH,
          new InboxEndpoint(
              EventTopics.EVT_10_DIEM_DA_TINH,
              ProfileSetupListener.SCORED_CONSUMER,
              PROFILE_AGGREGATE,
              listener,
              ProfileSetupListener.class.getMethod(
                  "onScored", DiemDaTinh.class, InboxEnvelope.class)));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }
}
