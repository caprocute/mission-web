package vn.viettel.okrkpi.task.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;
import vn.viettel.okrkpi.task.application.plan.PlanApprovalService;
import vn.viettel.okrkpi.task.application.plan.port.PlanSignatures;
import vn.viettel.okrkpi.task.infrastructure.client.HttpPlanSignatures;
import vn.viettel.okrkpi.task.infrastructure.listener.PlanSignatureConsumer;
import vn.viettel.okrkpi.task.infrastructure.web.PlanApprovalJobs;

/**
 * CORE-02-09: duyệt kế hoạch có ký số. Yêu cầu ký đi tới SVC-10 theo {@code
 * mission.task.plan-approval.esign-url}; consumer EVT-18 ghi inbox {@code task} (tắt bằng {@code
 * mission.task.plan-approval.consumer.enabled=false}).
 */
@Configuration(proxyBeanMethods = false)
public class PlanApprovalConfiguration {

  static final String ESIGN_INSTANCE = "esign-service";

  @Bean
  OutboxPlanApprovals planApprovalEvents(
      @Qualifier("taskDataSource") DataSource dataSource,
      @Value("${mission.task.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxPlanApprovals(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "task",
            new ApicurioPayloadSerializer(registry, java.util.Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            vn.viettel.okrkpi.shared.kernel.Clock.systemUtc()));
  }

  /** SVC-10 chưa cấu hình thì bước duyệt trả 503 để người duyệt thử lại sau. */
  @Bean
  @ConditionalOnMissingBean(PlanSignatures.class)
  PlanSignatures planSignatures(
      ResilientClientFactory factory,
      @Value("${mission.task.plan-approval.esign-url:${ESIGN_URL:}}") String url,
      @Value("${mission.task.plan-approval.esign-purpose:QUYET_DINH}") String purpose) {
    if (url == null || url.isBlank()) {
      return request -> {
        throw new DependencyUnavailableException(
            ESIGN_INSTANCE,
            DependencyUnavailableException.Reason.IO_ERROR,
            Duration.ofSeconds(30),
            null);
      };
    }
    return new HttpPlanSignatures(factory.create(ESIGN_INSTANCE, url), purpose);
  }

  @Bean
  PlanApprovalService planApprovalService(
      JdbcPlanRepository planRepository,
      PeriodApi periods,
      ObjectProvider<OrgDirectoryApi> org,
      PlanSignatures planSignatures,
      OutboxPlanApprovals planApprovalEvents,
      OutboxPlanNotices planNotices,
      ApplicationEventPublisher publisher) {
    return new PlanApprovalService(
        planRepository,
        periods,
        org.getIfAvailable(),
        planSignatures,
        planApprovalEvents,
        planNotices,
        publisher,
        Clock.systemUTC());
  }

  @Bean
  PlanApprovalJobs planApprovalJobs(PlanApprovalService planApprovalService) {
    return new PlanApprovalJobs(planApprovalService);
  }

  /**
   * Inbox riêng của schema {@code task} cho EVT-18 (inbox chung của mission-core ghi {@code
   * org_replica}); dùng chung bộ giải mã Avro, topic thử lại và thư chết của starter.
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "mission.task.plan-approval.consumer",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  PlanSignatureConsumer planSignatureConsumer(
      PlanApprovalService planApprovalService,
      @Qualifier("taskDataSource") DataSource dataSource,
      @Qualifier("taskTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<InboxProperties> properties,
      ObjectProvider<InboxPayloadDeserializer> deserializer,
      ObjectProvider<InboxRetryDlqPublisher> retryDlq,
      ObjectProvider<InboxMetrics> metrics,
      ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>> containers) {
    InboxProperties props = properties.getIfAvailable();
    InboxPayloadDeserializer d = deserializer.getIfAvailable();
    InboxRetryDlqPublisher r = retryDlq.getIfAvailable();
    InboxMetrics m = metrics.getIfAvailable();
    if (props == null || d == null || r == null || m == null) {
      return null;
    }
    InboxMessageProcessor processor =
        new InboxMessageProcessor(
            new JdbcTemplate(dataSource),
            tm,
            "task",
            d,
            r,
            m,
            props.retry(),
            vn.viettel.okrkpi.shared.kernel.Clock.systemUtc());
    return new PlanSignatureConsumer(
        planApprovalService, processor, containers, props.listenerAutoStartup());
  }
}
