package vn.viettel.okrkpi.policy.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.policy.application.DefaultPolicyQueryService;
import vn.viettel.okrkpi.policy.application.PolicyPackService;
import vn.viettel.okrkpi.policy.application.PolicyPeriodCalendar;
import vn.viettel.okrkpi.policy.application.PolicyResolution;
import vn.viettel.okrkpi.policy.application.PolicySchemaCatalog;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.infrastructure.web.PolicyIdempotency;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * CORE-05-02: nối Policy Pack của MOD-05 với {@code policyDataSource}; outbox ghi {@code
 * policy.outbox_event} và khoá Idempotency ghi {@code policy.idempotency_key} cùng giao dịch {@code
 * policyTransactionManager}. CORE-05-03: phân giải gói theo kỳ và hai cổng đảo phụ thuộc của MOD-08
 * ({@code PeriodCalendarPort}, {@code PolicySchemaPort}); hai cổng là bean dự phòng để kiểm thử
 * hoặc cell khai bean riêng thì bean đó thắng.
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
      ObjectProvider<ObjectMapper> json,
      ApplicationEventPublisher events) {
    PolicyPackService service =
        new PolicyPackService(
            policyPackRepository,
            policyAudit,
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc());
    service.setEvents(events);
    return service;
  }

  @Bean
  JdbcPolicyResolutionRepository policyResolutionRepository(
      @Qualifier("policyDataSource") DataSource dataSource) {
    return new JdbcPolicyResolutionRepository(JdbcClient.create(dataSource));
  }

  @Bean
  PolicyResolution policyResolution(
      JdbcPolicyResolutionRepository policyResolutionRepository,
      JdbcPolicyPackRepository policyPackRepository,
      ObjectProvider<PeriodApi> periodApi,
      ObjectProvider<ObjectMapper> json) {
    return new PolicyResolution(
        policyResolutionRepository,
        policyPackRepository,
        periodApi,
        json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  @Fallback
  PolicyPeriodCalendar policyPeriodCalendar(
      ObjectProvider<PolicyResolution> policyResolution,
      ObjectProvider<DefaultPolicyQueryService> policyQuery) {
    return new PolicyPeriodCalendar(policyResolution, policyQuery);
  }

  @Bean
  @Fallback
  PolicySchemaCatalog policySchemaCatalog(
      ObjectProvider<PolicyResolution> policyResolution,
      ObjectProvider<DefaultPolicyQueryService> policyQuery) {
    return new PolicySchemaCatalog(policyResolution, policyQuery);
  }

  @Bean
  PolicyIdempotency policyIdempotency(
      @Qualifier("policyDataSource") DataSource dataSource,
      @Qualifier("policyTransactionManager") PlatformTransactionManager tm,
      ObjectProvider<ObjectMapper> json) {
    return new PolicyIdempotency(dataSource, tm, json.getIfAvailable(ObjectMapper::new));
  }
}
