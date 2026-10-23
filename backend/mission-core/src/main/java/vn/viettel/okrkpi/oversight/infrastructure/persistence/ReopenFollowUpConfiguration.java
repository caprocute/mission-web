package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import vn.viettel.okrkpi.appraisal.api.ProfileFormsApi;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi;
import vn.viettel.okrkpi.config.api.PeriodReopenApi;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenFollowUpService;
import vn.viettel.okrkpi.oversight.infrastructure.client.AppraisalReopenForms;
import vn.viettel.okrkpi.oversight.infrastructure.client.AppraisalScopeProfiles;
import vn.viettel.okrkpi.oversight.infrastructure.client.ConfigPeriodRelock;
import vn.viettel.okrkpi.oversight.infrastructure.listener.ReopenExpiredListener;
import vn.viettel.okrkpi.oversight.infrastructure.listener.ReopenFollowUpListener;
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
import vn.vtokr.config.v1.KyDaKhoa;
import vn.vtokr.dossier.v1.HoSoSoDaSinh;
import vn.vtokr.esign.v1.KySoDaHoanTat;
import vn.vtokr.scoring.v1.DiemDaTinh;

/**
 * Điều phối hậu kiểm sau EVT-22 (CORE-07-09): kho tiến độ, dịch vụ điều phối, bộ nghe hết hạn mở
 * lại của MOD-08 và consumer EVT-09, EVT-10, EVT-17, EVT-18 qua inbox của schema oversight. Biểu
 * mẫu phải sinh lại, ký lại cho mỗi hồ sơ đặt ở {@code mission.oversight.post-audit.required-forms}
 * (mặc định Mẫu 2, Mẫu 3).
 */
@Configuration(proxyBeanMethods = false)
public class ReopenFollowUpConfiguration {

  @Bean
  JdbcReopenProgressRepository reopenProgressRepository(
      @Qualifier("oversightDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcReopenProgressRepository(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  ReopenFollowUpService reopenFollowUpService(
      JdbcReopenRequestRepository requests,
      JdbcReopenProgressRepository progress,
      ObjectProvider<ProfileParticipantsApi> participants,
      ObjectProvider<ProfileFormsApi> forms,
      ObjectProvider<PeriodReopenApi> reopens,
      ObjectProvider<JdbcComplaintRepository> complaints,
      @Qualifier("oversightDataSource") DataSource dataSource,
      @Value("${mission.oversight.outbox.registry-url:${okrkpi.inbox.registry.url:}}")
          String registry,
      @Value("${mission.oversight.post-audit.required-forms:MAU_2,MAU_3}") List<String> required,
      ObjectProvider<OutboxPayloadSerializer> serializer,
      ObjectProvider<ObjectMapper> json) {
    OutboxWriter outbox =
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "oversight",
            serializer.getIfAvailable(() -> new ApicurioPayloadSerializer(registry, Map.of())),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc());
    PeriodReopenApi relock = reopens.getIfAvailable();
    Set<String> codes = new LinkedHashSet<>();
    required.stream().map(String::strip).filter(s -> !s.isEmpty()).forEach(codes::add);
    return new ReopenFollowUpService(
        requests,
        progress,
        new AppraisalScopeProfiles(participants.getIfAvailable()),
        new AppraisalReopenForms(forms.getIfAvailable()),
        relock == null ? null : new ConfigPeriodRelock(relock),
        complaints.getIfAvailable(),
        new OutboxReopenNotices(outbox),
        new OutboxOversightAudit(outbox),
        java.time.Clock.systemUTC(),
        codes);
  }

  @Bean
  ReopenExpiredListener reopenExpiredListener(ReopenFollowUpService reopenFollowUpService) {
    return new ReopenExpiredListener(reopenFollowUpService);
  }

  /**
   * Consumer EVT-09, EVT-10, EVT-17, EVT-18 của điều phối hậu kiểm; cần {@code
   * okrkpi.inbox.schema}, tắt bằng {@code mission.oversight.follow-up-listener.enabled=false}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema")
  @ConditionalOnProperty(
      prefix = "mission.oversight.follow-up-listener",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class FollowUpInbox {

    @Bean
    ReopenFollowUpListener reopenFollowUpListener(ReopenFollowUpService reopenFollowUpService) {
      return new ReopenFollowUpListener(reopenFollowUpService);
    }

    @Bean
    ConcurrentMessageListenerContainer<String, byte[]> reopenFollowUpContainer(
        ReopenFollowUpListener listener,
        @Qualifier("oversightDataSource") DataSource dataSource,
        @Qualifier("oversightTransactionManager") PlatformTransactionManager tm,
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
              "oversight",
              deserializer,
              retryDlqPublisher,
              metrics,
              properties == null ? null : properties.retry(),
              Clock.systemUtc());
      Map<String, InboxEndpoint> endpoints = new HashMap<>();
      for (InboxEndpoint e : endpoints(listener)) {
        endpoints.put(e.topic(), e);
      }
      Map<String, Object> props = new HashMap<>();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
      props.put(ConsumerConfig.GROUP_ID_CONFIG, "mission-core-oversight.reopen-follow-up");
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
      ContainerProperties container =
          new ContainerProperties(endpoints.keySet().toArray(String[]::new));
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

  /**
   * Điểm nhận của bốn sự kiện; không kiểm phiên bản aggregate vì thứ tự được xử lý theo thời điểm
   * trong sự kiện.
   */
  public static List<InboxEndpoint> endpoints(ReopenFollowUpListener listener) {
    try {
      Class<ReopenFollowUpListener> c = ReopenFollowUpListener.class;
      return List.of(
          new InboxEndpoint(
              EventTopics.EVT_10_DIEM_DA_TINH,
              ReopenFollowUpListener.RECOMPUTED_CONSUMER,
              null,
              listener,
              c.getMethod("onRecomputed", DiemDaTinh.class, InboxEnvelope.class)),
          new InboxEndpoint(
              EventTopics.EVT_17_HO_SO_SO_DA_SINH,
              ReopenFollowUpListener.GENERATED_CONSUMER,
              null,
              listener,
              c.getMethod("onGenerated", HoSoSoDaSinh.class, InboxEnvelope.class)),
          new InboxEndpoint(
              EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
              ReopenFollowUpListener.SIGNED_CONSUMER,
              null,
              listener,
              c.getMethod("onSigned", KySoDaHoanTat.class, InboxEnvelope.class)),
          new InboxEndpoint(
              EventTopics.EVT_09_KY_DA_KHOA,
              ReopenFollowUpListener.RELOCKED_CONSUMER,
              null,
              listener,
              c.getMethod("onRelocked", KyDaKhoa.class, InboxEnvelope.class)));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }
}
