package vn.viettel.okrkpi.policy.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.policy.application.GoldenTestCaseService;
import vn.viettel.okrkpi.policy.application.PolicyDecisionTableService;
import vn.viettel.okrkpi.policy.application.PolicyPackService;
import vn.viettel.okrkpi.policy.application.PolicyPeriodCalendar;
import vn.viettel.okrkpi.policy.application.PolicyResolutionService;
import vn.viettel.okrkpi.policy.application.PolicyRuleService;
import vn.viettel.okrkpi.policy.application.PolicySchemaAdapter;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.infrastructure.signing.KeyPairPolicyCaseSigner;
import vn.viettel.okrkpi.policy.infrastructure.web.PolicyIdempotency;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * CORE-05-02: nối Policy Pack của MOD-05 với {@code policyDataSource}; outbox ghi {@code
 * policy.outbox_event} và khoá Idempotency ghi {@code policy.idempotency_key} cùng giao dịch {@code
 * policyTransactionManager}.
 */
@Configuration(proxyBeanMethods = false)
public class PolicyPackConfiguration {

  @Bean
  JdbcPolicyPackRepository policyPackRepository(
      @Qualifier("policyDataSource") DataSource dataSource) {
    return new JdbcPolicyPackRepository(JdbcClient.create(dataSource));
  }

  @Bean
  OutboxPolicyAudit policyAudit(
      @Qualifier("policyDataSource") DataSource dataSource,
      @Value("${mission.policy.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxPolicyAudit(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "policy",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  PolicyPackService policyPackService(
      JdbcPolicyPackRepository policyPackRepository,
      PolicyAudit policyAudit,
      ObjectProvider<ObjectMapper> json) {
    return new PolicyPackService(
        policyPackRepository,
        policyAudit,
        json.getIfAvailable(ObjectMapper::new),
        Clock.systemUtc());
  }

  @Bean
  JdbcPolicyDecisionTableRepository policyDecisionTableRepository(
      @Qualifier("policyDataSource") DataSource dataSource) {
    return new JdbcPolicyDecisionTableRepository(JdbcClient.create(dataSource));
  }

  @Bean
  PolicyDecisionTableService policyDecisionTableService(
      JdbcPolicyPackRepository policyPackRepository,
      JdbcPolicyDecisionTableRepository policyDecisionTableRepository,
      PolicyAudit policyAudit,
      ObjectProvider<ObjectMapper> json) {
    return new PolicyDecisionTableService(
        policyPackRepository,
        policyDecisionTableRepository,
        policyAudit,
        json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  JdbcGoldenTestCaseRepository goldenTestCaseRepository(
      @Qualifier("policyDataSource") DataSource dataSource) {
    return new JdbcGoldenTestCaseRepository(JdbcClient.create(dataSource));
  }

  /** Khoá ký ca kiểm thử vàng (CORE-05-06); bí mật nạp qua biến môi trường, không ghi trong mã. */
  @Bean
  KeyPairPolicyCaseSigner policyCaseSigner(
      @Value("${mission.policy.case-signing.key-id:policy-golden}") String keyId,
      @Value("${mission.policy.case-signing.private-key:}") String privateKey,
      @Value("${mission.policy.case-signing.public-key:}") String publicKey) {
    return KeyPairPolicyCaseSigner.of(keyId, privateKey, publicKey);
  }

  @Bean
  GoldenTestCaseService goldenTestCaseService(
      JdbcPolicyPackRepository policyPackRepository,
      JdbcGoldenTestCaseRepository goldenTestCaseRepository,
      KeyPairPolicyCaseSigner policyCaseSigner,
      PolicyAudit policyAudit,
      ObjectProvider<ObjectMapper> json) {
    return new GoldenTestCaseService(
        policyPackRepository,
        goldenTestCaseRepository,
        policyCaseSigner,
        policyAudit,
        json.getIfAvailable(ObjectMapper::new),
        Clock.systemUtc());
  }

  @Bean
  JdbcPolicyRuleRepository policyRuleRepository(
      @Qualifier("policyDataSource") DataSource dataSource) {
    return new JdbcPolicyRuleRepository(JdbcClient.create(dataSource));
  }

  @Bean
  PolicyRuleService policyRuleService(
      JdbcPolicyPackRepository policyPackRepository,
      JdbcPolicyRuleRepository policyRuleRepository,
      PolicyAudit policyAudit,
      ObjectProvider<ObjectMapper> json) {
    return new PolicyRuleService(
        policyPackRepository,
        policyRuleRepository,
        policyAudit,
        json.getIfAvailable(ObjectMapper::new),
        Clock.systemUtc());
  }

  @Bean
  JdbcPolicyResolutionRepository policyResolutionRepository(
      @Qualifier("policyDataSource") DataSource dataSource) {
    return new JdbcPolicyResolutionRepository(JdbcClient.create(dataSource));
  }

  /** Bộ phân giải theo kỳ (CORE-05-03); đệm trong pod, bỏ đệm khi phát hành hoặc ngừng gói. */
  @Bean
  PolicyResolutionService policyResolutionService(
      JdbcPolicyResolutionRepository policyResolutionRepository,
      JdbcPolicyPackRepository policyPackRepository,
      ObjectProvider<PeriodApi> periodApi,
      PolicyPackService policyPackService,
      @Value("${mission.policy.resolution-cache.ttl:5m}") Duration ttl,
      ObjectProvider<ObjectMapper> json) {
    PolicyResolutionService service =
        new PolicyResolutionService(
            policyResolutionRepository,
            policyPackRepository,
            periodApi::getObject,
            json.getIfAvailable(ObjectMapper::new),
            ttl);
    policyPackService.onEffectiveChange(service::evict);
    return service;
  }

  /** Cổng lịch kỳ của MOD-08 (đảo phụ thuộc). */
  @Bean
  PolicyPeriodCalendar policyPeriodCalendar(
      PolicyResolutionService policyResolutionService,
      ObjectProvider<WorkingCalendarApi> workingCalendar) {
    return new PolicyPeriodCalendar(policyResolutionService, workingCalendar::getIfAvailable);
  }

  /** Cổng schema biểu mẫu của MOD-08 (đảo phụ thuộc). */
  @Bean
  PolicySchemaAdapter policySchemaAdapter(
      PolicyResolutionService policyResolutionService,
      JdbcPolicyPackRepository policyPackRepository) {
    return new PolicySchemaAdapter(policyResolutionService, policyPackRepository);
  }

  @Bean
  PolicyIdempotency policyIdempotency(
      @Qualifier("policyDataSource") DataSource dataSource,
      @Qualifier("policyTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<ObjectMapper> json) {
    return new PolicyIdempotency(dataSource, tm, json.getIfAvailable(ObjectMapper::new));
  }
}
