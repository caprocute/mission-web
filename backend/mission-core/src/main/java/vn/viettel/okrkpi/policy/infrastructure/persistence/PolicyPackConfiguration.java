package vn.viettel.okrkpi.policy.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import vn.viettel.okrkpi.policy.application.PolicyPackService;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
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
  PolicyIdempotency policyIdempotency(
      @Qualifier("policyDataSource") DataSource dataSource,
      @Qualifier("policyTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<ObjectMapper> json) {
    return new PolicyIdempotency(dataSource, tm, json.getIfAvailable(ObjectMapper::new));
  }
}
