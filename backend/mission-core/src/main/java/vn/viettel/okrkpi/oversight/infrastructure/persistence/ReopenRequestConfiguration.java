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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.RoleHolderApi;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.oversight.application.port.ApprovalSigner;
import vn.viettel.okrkpi.oversight.application.port.ReopenImpactSimulator;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenApprovalService;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenFollowUpService;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenRequestService;
import vn.viettel.okrkpi.oversight.infrastructure.client.EsignApprovalSigner;
import vn.viettel.okrkpi.oversight.infrastructure.client.ModuleApproverDirectory;
import vn.viettel.okrkpi.oversight.infrastructure.listener.ReopenSignedListener;
import vn.viettel.okrkpi.oversight.infrastructure.scheduling.ReopenExecutionJob;
import vn.viettel.okrkpi.oversight.infrastructure.web.OversightIdempotency;
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
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.vtokr.esign.v1.KySoDaHoanTat;

/**
 * Yêu cầu mở lại kỳ (CORE-07-07): kho JDBC trên {@code oversightDataSource}, dịch vụ ứng dụng, sổ
 * cái qua {@code oversight.outbox_event} và Idempotency-Key trên {@code oversight.idempotency_key}.
 * Bộ mô phỏng {@link ReopenImpactSimulator} là bean tuỳ chọn (DV-07-08).
 */
@Configuration(proxyBeanMethods = false)
public class ReopenRequestConfiguration {

  @Bean
  JdbcReopenRequestRepository reopenRequestRepository(
      @Qualifier("oversightDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcReopenRequestRepository(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  /**
   * Bốn mắt (CORE-07-08): người duyệt từ MOD-01 và MOD-04, ký số ở SVC-10 ({@code
   * mission.oversight.esign.base-url} hoặc bean {@link ApprovalSigner}; không có thì duyệt, từ chối
   * trả 503), EVT-21 và EVT-22 qua {@code oversight.outbox_event}.
   */
  @Bean
  ReopenApprovalService reopenApprovalService(
      JdbcReopenRequestRepository repository,
      PeriodApi periods,
      RoleHolderApi roleHolders,
      ObjectProvider<AccessDirectory> directory,
      ObjectProvider<ProfileParticipantsApi> participants,
      @Qualifier("oversightDataSource") DataSource dataSource,
      @Value("${mission.oversight.outbox.registry-url:${okrkpi.inbox.registry.url:}}")
          String registry,
      @Value("${mission.oversight.esign.base-url:}") String esignUrl,
      ObjectProvider<ApprovalSigner> signer,
      ObjectProvider<OutboxPayloadSerializer> serializer,
      ObjectProvider<ObjectMapper> json) {
    OutboxWriter outbox = outbox(dataSource, registry, serializer, json);
    return new ReopenApprovalService(
        repository,
        new ModuleApproverDirectory(
            roleHolders, directory.getIfAvailable(), participants.getIfAvailable()),
        signer.getIfAvailable(
            () ->
                esignUrl == null || esignUrl.isBlank()
                    ? null
                    : new EsignApprovalSigner(
                        new EsignRequestClient(esignUrl, json.getIfAvailable(ObjectMapper::new)))),
        new OutboxReopenNotices(outbox),
        new OutboxReopenEvents(outbox),
        new OutboxOversightAudit(outbox),
        periods,
        java.time.Clock.systemUTC());
  }

  @Bean
  ReopenRequestService reopenRequestService(
      JdbcReopenRequestRepository repository,
      PeriodApi periods,
      ReopenApprovalService approvals,
      ObjectProvider<WorkingCalendarApi> calendar,
      ObjectProvider<AccessDirectory> directory,
      ObjectProvider<ReopenImpactSimulator> simulator,
      @Qualifier("oversightDataSource") DataSource dataSource,
      @Value("${mission.oversight.outbox.registry-url:${okrkpi.inbox.registry.url:}}")
          String registry,
      ObjectProvider<OutboxPayloadSerializer> serializer,
      ObjectProvider<ObjectMapper> json) {
    return new ReopenRequestService(
        repository,
        periods,
        calendar.getIfAvailable(),
        directory.getIfAvailable(),
        new OutboxOversightAudit(outbox(dataSource, registry, serializer, json)),
        simulator.getIfAvailable(),
        java.time.Clock.systemUTC(),
        approvals);
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

  /**
   * Consumer EVT-18 cho quyết định duyệt mở lại kỳ; cần {@code okrkpi.inbox.schema}, tắt bằng
   * {@code mission.oversight.esign-listener.enabled=false}.
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
    ReopenSignedListener reopenSignedListener(ReopenApprovalService reopenApprovalService) {
      return new ReopenSignedListener(reopenApprovalService);
    }

    @Bean
    ConcurrentMessageListenerContainer<String, byte[]> reopenSignedContainer(
        ReopenSignedListener listener,
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
      props.put(ConsumerConfig.GROUP_ID_CONFIG, "mission-core-" + ReopenSignedListener.CONSUMER);
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
  public static InboxEndpoint signedEndpoint(ReopenSignedListener listener) {
    try {
      return new InboxEndpoint(
          EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
          ReopenSignedListener.CONSUMER,
          null,
          listener,
          ReopenSignedListener.class.getMethod("on", KySoDaHoanTat.class, InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Xác nhận yêu cầu đã thực hiện; tắt bằng {@code
   * mission.oversight.reopen-execution.enabled=false}.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "mission.oversight.reopen-execution",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class ExecutionJob {

    @Bean
    ReopenExecutionJob reopenExecutionJob(
        ReopenApprovalService reopenApprovalService,
        ObjectProvider<ReopenFollowUpService> followUp,
        ObjectProvider<TenantDirectory> directory,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
      return new ReopenExecutionJob(
          reopenApprovalService, followUp.getIfAvailable(), directory, cell);
    }
  }

  @Bean
  OversightIdempotency oversightIdempotency(
      @Qualifier("oversightDataSource") DataSource dataSource,
      @Qualifier("oversightTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<ObjectMapper> json) {
    return new OversightIdempotency(dataSource, tm, json.getIfAvailable(ObjectMapper::new));
  }
}
