package vn.viettel.okrkpi.policy.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.UiPackCompatibilityApi;
import vn.viettel.okrkpi.policy.application.PolicyPublishSagaService;
import vn.viettel.okrkpi.policy.application.PolicyResolutionService;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackDistribution;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishedEvents;
import vn.viettel.okrkpi.policy.application.port.PolicyWorkflowActivation;
import vn.viettel.okrkpi.policy.infrastructure.client.LocalPolicyPackDistribution;
import vn.viettel.okrkpi.policy.infrastructure.client.PendingPolicyWorkflowActivation;
import vn.viettel.okrkpi.policy.infrastructure.job.JdbcPolicyPublishJobs;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * CORE-05-10: saga phát hành Policy Pack, phát hành lại, thu hồi, tác vụ {@code POLICY_PUBLISH}.
 */
@Configuration(proxyBeanMethods = false)
public class PolicyPublishConfiguration {

  @Bean
  JdbcPolicyPublishSagaRepository policyPublishSagaRepository(
      @Qualifier("policyDataSource") DataSource dataSource,
      JdbcPolicyPackRepository policyPackRepository,
      ObjectProvider<ObjectMapper> json) {
    return new JdbcPolicyPublishSagaRepository(
        JdbcClient.create(dataSource),
        json.getIfAvailable(ObjectMapper::new),
        policyPackRepository);
  }

  @Bean
  JdbcPolicyPublishJobs policyPublishJobs(
      JdbcPolicyPublishSagaRepository policyPublishSagaRepository,
      JdbcPolicyPackRepository policyPackRepository,
      @Qualifier("policyTransactionManager") PlatformTransactionManager tm) {
    TransactionTemplate reads = new TransactionTemplate(tm);
    reads.setReadOnly(true);
    return new JdbcPolicyPublishJobs(policyPublishSagaRepository, policyPackRepository, reads);
  }

  @Bean
  LocalPolicyPackDistribution policyPackDistribution() {
    return new LocalPolicyPackDistribution();
  }

  @Bean
  PendingPolicyWorkflowActivation policyWorkflowActivation() {
    return new PendingPolicyWorkflowActivation();
  }

  @Bean
  OutboxPolicyPublishedEvents policyPublishedEvents(
      @Qualifier("policyDataSource") DataSource dataSource,
      @Value("${mission.policy.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxPolicyPublishedEvents(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "policy",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  /**
   * Luồng chạy saga phát hành, tách khỏi luồng chạy kiểm thử để lần chạy kiểm thử dài không làm
   * saga chờ. Bọc trong record để không làm cấu hình tự động {@code applicationTaskExecutor} lùi
   * bước.
   */
  @Bean(destroyMethod = "close")
  PublishSagaThreads policyPublishSagaThreads() {
    return new PublishSagaThreads(
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("policy-publish-", 0).factory()));
  }

  /** Bộ luồng riêng của saga phát hành Policy Pack. */
  public record PublishSagaThreads(ExecutorService pool) implements AutoCloseable {
    @Override
    public void close() {
      pool.shutdown();
    }
  }

  @Bean
  @SuppressWarnings("java:S107")
  PolicyPublishSagaService policyPublishSagaService(
      JdbcPolicyPackRepository policyPackRepository,
      JdbcPolicyPublishRepository policyPublishRepository,
      JdbcPolicyPublishSagaRepository policyPublishSagaRepository,
      UiPackCompatibilityApi uiPackCompatibilityApi,
      PeriodApi periodApi,
      PolicyPackDistribution policyPackDistribution,
      PolicyWorkflowActivation policyWorkflowActivation,
      PolicyPublishedEvents policyPublishedEvents,
      PolicyAudit policyAudit,
      PolicyResolutionService policyResolutionService,
      PublishSagaThreads policyPublishSagaThreads,
      @Qualifier("policyTransactionManager") PlatformTransactionManager tm,
      @Value("${mission.policy.publish.retry-delay:PT2S}") Duration retryDelay,
      ObjectProvider<ObjectMapper> json) {
    return new PolicyPublishSagaService(
        policyPackRepository,
        policyPublishRepository,
        policyPublishSagaRepository,
        uiPackCompatibilityApi,
        periodApi,
        policyPackDistribution,
        policyWorkflowActivation,
        policyPublishedEvents,
        policyAudit,
        policyResolutionService,
        json.getIfAvailable(ObjectMapper::new),
        Clock.systemUtc(),
        policyPublishSagaThreads.pool(),
        new TransactionTemplate(tm),
        retryDelay);
  }
}
