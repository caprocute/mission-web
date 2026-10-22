package vn.viettel.okrkpi.appraisal.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
import vn.viettel.okrkpi.appraisal.application.decision.DecisionSigningService;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentSubmission;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentNotices;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SignRequests;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.appraisal.infrastructure.client.EsignSignRequests;
import vn.viettel.okrkpi.appraisal.infrastructure.evidence.ProductConfirmedEvidence;
import vn.viettel.okrkpi.appraisal.infrastructure.listener.SelfAssessmentSignedListener;
import vn.viettel.okrkpi.appraisal.infrastructure.web.SelfAssessmentJobs;
import vn.viettel.okrkpi.config.api.FormValidationApi;
import vn.viettel.okrkpi.events.EventTopics;
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
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.esign.v1.KySoDaHoanTat;

/**
 * CORE-04-04: phiếu tự đánh giá trên {@code appraisalDataSource}, mã hoá C2 bằng {@code
 * FieldEncryptor}, mở yêu cầu ký ở SVC-10 ({@code mission.appraisal.esign.base-url}), tác vụ theo
 * dõi ký và consumer EVT-18 qua inbox của schema appraisal.
 */
@Configuration(proxyBeanMethods = false)
public class SelfAssessmentConfiguration {

  @Bean
  JdbcSelfAssessmentRepository selfAssessmentRepository(
      @Qualifier("appraisalDataSource") DataSource dataSource) {
    return new JdbcSelfAssessmentRepository(JdbcClient.create(dataSource));
  }

  /** Không có {@code FieldEncryptor} thì từ chối ghi, không bao giờ lưu C2 dạng rõ. */
  @Bean
  TextSealer appraisalTextSealer(ObjectProvider<FieldEncryptor> encryptor) {
    FieldEncryptor e = encryptor.getIfAvailable();
    if (e != null) {
      return new FieldEncryptorTextSealer(e);
    }
    return new TextSealer() {
      @Override
      public SealedText seal(TenantId tenant, String column, UUID rowId, String plain) {
        throw new IllegalStateException("Chưa cấu hình mã hoá trường C2");
      }

      @Override
      public String open(TenantId tenant, String column, UUID rowId, SealedText sealed) {
        throw new IllegalStateException("Chưa cấu hình mã hoá trường C2");
      }
    };
  }

  @Bean
  SelfAssessmentNotices selfAssessmentNotices(
      @Qualifier("appraisalDataSource") DataSource dataSource,
      @Value("${mission.appraisal.outbox.registry-url:${okrkpi.inbox.registry.url}}")
          String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxSelfAssessmentNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "appraisal",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  SelfAssessmentService selfAssessmentService(
      JdbcAppraisalProfileRepository appraisalProfileRepository,
      JdbcSelfAssessmentRepository selfAssessmentRepository,
      AppraisalProfileService appraisalProfileService,
      TextSealer appraisalTextSealer,
      ObjectProvider<FormValidationApi> validator,
      ObjectProvider<EvidenceQueryApi> evidenceQuery,
      SelfAssessmentNotices selfAssessmentNotices,
      ObjectProvider<ObjectMapper> json) {
    EvidenceQueryApi evidenceApi = evidenceQuery.getIfAvailable();
    return new SelfAssessmentService(
        appraisalProfileRepository,
        selfAssessmentRepository,
        appraisalProfileService,
        appraisalTextSealer,
        validator.getIfAvailable(),
        evidenceApi == null ? null : new ProductConfirmedEvidence(evidenceApi),
        selfAssessmentNotices,
        json.getIfAvailable(ObjectMapper::new),
        java.time.Clock.systemUTC());
  }

  /**
   * Chưa đặt {@code mission.appraisal.esign.base-url} thì lệnh nộp báo MSG-09-05 và giữ nguyên dữ
   * liệu.
   */
  @Bean
  SignRequests appraisalSignRequests(
      @Value("${mission.appraisal.esign.base-url:}") String baseUrl,
      ObjectProvider<ObjectMapper> json) {
    if (baseUrl.isBlank()) {
      return (tenant, profileId, provider, key, authorization) -> {
        throw new IllegalStateException("Chưa cấu hình địa chỉ dịch vụ ký số");
      };
    }
    return new EsignSignRequests(
        new EsignRequestClient(baseUrl, json.getIfAvailable(ObjectMapper::new)));
  }

  @Bean
  SelfAssessmentSubmission selfAssessmentSubmission(
      SelfAssessmentService selfAssessmentService, SignRequests appraisalSignRequests) {
    return new SelfAssessmentSubmission(selfAssessmentService, appraisalSignRequests);
  }

  @Bean
  SelfAssessmentJobs selfAssessmentJobs(SelfAssessmentService selfAssessmentService) {
    return new SelfAssessmentJobs(selfAssessmentService);
  }

  /**
   * Consumer EVT-18 qua inbox của schema appraisal. Tắt bằng {@code
   * mission.appraisal.esign-listener.enabled=false}; khởi động theo {@code
   * okrkpi.inbox.listener-auto-startup}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "okrkpi.inbox", name = "schema")
  @ConditionalOnProperty(
      prefix = "mission.appraisal.esign-listener",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class SignedInbox {

    @Bean
    SelfAssessmentSignedListener selfAssessmentSignedListener(
        SelfAssessmentService selfAssessmentService,
        ObjectProvider<ReviewService> reviews,
        ObjectProvider<DecisionSigningService> decisions) {
      return new SelfAssessmentSignedListener(
          selfAssessmentService, reviews.getIfAvailable(), decisions.getIfAvailable());
    }

    @Bean
    ConcurrentMessageListenerContainer<String, byte[]> selfAssessmentSignedContainer(
        SelfAssessmentSignedListener listener,
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
      InboxEndpoint endpoint = signedEndpoint(listener);
      Map<String, Object> props = new HashMap<>();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
      props.put(
          ConsumerConfig.GROUP_ID_CONFIG, "mission-core-" + SelfAssessmentSignedListener.CONSUMER);
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
  public static InboxEndpoint signedEndpoint(SelfAssessmentSignedListener listener) {
    try {
      return new InboxEndpoint(
          EventTopics.EVT_18_KY_SO_DA_HOAN_TAT,
          SelfAssessmentSignedListener.CONSUMER,
          null,
          listener,
          SelfAssessmentSignedListener.class.getMethod(
              "on", KySoDaHoanTat.class, InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }
}
