package vn.viettel.okrkpi.oversight.infrastructure.persistence;

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
import vn.viettel.okrkpi.appraisal.api.ComplaintProfilesApi;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.RoleHolderApi;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintHandlingService;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSealer;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSigner;
import vn.viettel.okrkpi.oversight.infrastructure.client.EsignComplaintSigner;
import vn.viettel.okrkpi.oversight.infrastructure.client.ModuleComplaintHandlerDirectory;
import vn.viettel.okrkpi.oversight.infrastructure.listener.ComplaintSignedListener;
import vn.viettel.okrkpi.oversight.infrastructure.web.ComplaintSignJobs;
import vn.viettel.okrkpi.product.api.EvidenceQueryApi;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.infrastructure.esign.EsignRequestClient;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.esign.v1.KySoDaHoanTat;

/**
 * Khiếu nại (CORE-07-02): kho JDBC trên {@code oversightDataSource}, mã hoá C2 bằng {@code
 * FieldEncryptor} (không có thì từ chối ghi), ký số ở SVC-10 ({@code
 * mission.oversight.esign.base-url} hoặc bean {@link ComplaintSigner}; không có thì gửi trả 503),
 * EVT-25 và EVT-21 qua {@code oversight.outbox_event}, consumer EVT-18 và tác vụ {@code ESIGN}.
 */
@Configuration(proxyBeanMethods = false)
public class ComplaintConfiguration {

  @Bean
  JdbcComplaintRepository complaintRepository(
      @Qualifier("oversightDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcComplaintRepository(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  ComplaintService complaintService(
      JdbcComplaintRepository complaintRepository,
      ComplaintProfilesApi profiles,
      PeriodApi periods,
      ObjectProvider<WorkingCalendarApi> calendar,
      ObjectProvider<ComplaintSealer> sealer,
      ObjectProvider<FieldEncryptor> encryptor,
      ObjectProvider<ComplaintSigner> signer,
      @Qualifier("oversightDataSource") DataSource dataSource,
      @Value("${mission.oversight.outbox.registry-url:${okrkpi.inbox.registry.url:}}")
          String registry,
      @Value("${mission.oversight.esign.base-url:}") String esignUrl,
      ObjectProvider<OutboxPayloadSerializer> serializer,
      ObjectProvider<ObjectMapper> json) {
    OutboxWriter outbox = outbox(dataSource, registry, serializer, json);
    FieldEncryptor e = encryptor.getIfAvailable();
    return new ComplaintService(
        complaintRepository,
        profiles,
        periods,
        calendar.getIfAvailable(),
        sealer.getIfAvailable(() -> e == null ? null : new FieldEncryptorComplaintSealer(e)),
        signer.getIfAvailable(
            () ->
                esignUrl == null || esignUrl.isBlank()
                    ? null
                    : new EsignComplaintSigner(
                        new EsignRequestClient(esignUrl, json.getIfAvailable(ObjectMapper::new)))),
        new OutboxComplaintEvents(outbox),
        new OutboxOversightAudit(outbox),
        java.time.Clock.systemUTC());
  }

  @Bean
  ComplaintHandlingService complaintHandlingService(
      JdbcComplaintRepository complaintRepository,
      ComplaintService complaintService,
      ComplaintProfilesApi profiles,
      RoleHolderApi roleHolders,
      ObjectProvider<AccessDirectory> units,
      ObjectProvider<EvidenceQueryApi> evidence,
      @Qualifier("oversightDataSource") DataSource dataSource,
      @Value("${mission.oversight.outbox.registry-url:${okrkpi.inbox.registry.url:}}")
          String registry,
      ObjectProvider<OutboxPayloadSerializer> serializer,
      ObjectProvider<ObjectMapper> json) {
    OutboxWriter outbox = outbox(dataSource, registry, serializer, json);
    return new ComplaintHandlingService(
        complaintRepository,
        complaintService,
        profiles,
        new ModuleComplaintHandlerDirectory(
            roleHolders, units.getIfAvailable(), profiles, evidence.getIfAvailable()),
        new OutboxComplaintEvents(outbox),
        new OutboxOversightAudit(outbox),
        java.time.Clock.systemUTC());
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

  @Bean
  ComplaintSignJobs complaintSignJobs(ComplaintService complaintService) {
    return new ComplaintSignJobs(complaintService);
  }

  /**
   * Consumer EVT-18 cho nội dung khiếu nại; cần {@code okrkpi.inbox.schema}, tắt bằng {@code
   * mission.oversight.esign-listener.enabled=false} như consumer của mở lại kỳ.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema")
  @ConditionalOnProperty(
      prefix = "mission.oversight.esign-listener",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class SignedInbox {

    @Bean
    ComplaintSignedListener complaintSignedListener(ComplaintService complaintService) {
      return new ComplaintSignedListener(complaintService);
    }

    @Bean
    ConcurrentMessageListenerContainer<String, byte[]> complaintSignedContainer(
        ComplaintSignedListener listener,
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
      InboxEndpoint endpoint = signedEndpoint(listener);
      Map<String, Object> props = new HashMap<>();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
      props.put(ConsumerConfig.GROUP_ID_CONFIG, "mission-core-" + ComplaintSignedListener.CONSUMER);
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
      ContainerProperties container = new ContainerProperties(EventTopics.EVT_18_KY_SO_DA_HOAN_TAT);
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

  /** Điểm nhận EVT-18; không kiểm phiên bản aggregate (mỗi yêu cầu ký chỉ hoàn tất một lần). */
  public static InboxEndpoint signedEndpoint(ComplaintSignedListener listener) {
    try {
      return new InboxEndpoint(
          EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
          ComplaintSignedListener.CONSUMER,
          null,
          listener,
          ComplaintSignedListener.class.getMethod("on", KySoDaHoanTat.class, InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }
}
