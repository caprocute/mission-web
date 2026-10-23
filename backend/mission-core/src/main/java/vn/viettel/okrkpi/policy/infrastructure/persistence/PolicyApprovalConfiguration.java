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
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.policy.application.PolicyApprovalService;
import vn.viettel.okrkpi.policy.application.port.PolicyApprovalNotices;
import vn.viettel.okrkpi.policy.application.port.PolicyApprovers;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishLauncher;
import vn.viettel.okrkpi.policy.infrastructure.client.KeycloakPolicyApprovers;
import vn.viettel.okrkpi.policy.infrastructure.signing.KeyPairPolicyCaseSigner;
import vn.viettel.okrkpi.policy.infrastructure.signing.PlatformKeyPolicyPackSigner;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/** CORE-05-09: trình, phê duyệt có ký số và từ chối phát hành Policy Pack. */
@Configuration(proxyBeanMethods = false)
public class PolicyApprovalConfiguration {

  @Bean
  JdbcPolicyPublishRepository policyPublishRepository(
      @Qualifier("policyDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcPolicyPublishRepository(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  PlatformKeyPolicyPackSigner policyPackSigner(KeyPairPolicyCaseSigner policyCaseSigner) {
    return new PlatformKeyPolicyPackSigner(policyCaseSigner, Clock.systemUtc());
  }

  @Bean
  OutboxPolicyApprovalNotices policyApprovalNotices(
      @Qualifier("policyDataSource") DataSource dataSource,
      @Value("${mission.policy.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    ObjectMapper mapper = json.getIfAvailable(ObjectMapper::new);
    return new OutboxPolicyApprovalNotices(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "policy",
            new ApicurioPayloadSerializer(registry, Map.of()),
            mapper,
            Clock.systemUtc()),
        mapper);
  }

  @Bean
  KeycloakPolicyApprovers policyApprovers(ObjectProvider<KeycloakAdminClient> keycloak) {
    return new KeycloakPolicyApprovers(keycloak);
  }

  @Bean
  PolicyApprovalService policyApprovalService(
      JdbcPolicyPackRepository policyPackRepository,
      JdbcPolicyPublishRepository policyPublishRepository,
      JdbcPolicyTestRunRepository policyTestRunRepository,
      PeriodApi periodApi,
      PolicyPackSigner policyPackSigner,
      PolicyPublishLauncher policyPublishJobs,
      PolicyApprovalNotices policyApprovalNotices,
      PolicyApprovers policyApprovers,
      PolicyAudit policyAudit) {
    return new PolicyApprovalService(
        policyPackRepository,
        policyPublishRepository,
        policyTestRunRepository,
        periodApi,
        policyPackSigner,
        policyPublishJobs,
        policyApprovalNotices,
        policyApprovers,
        policyAudit,
        Clock.systemUtc());
  }
}
