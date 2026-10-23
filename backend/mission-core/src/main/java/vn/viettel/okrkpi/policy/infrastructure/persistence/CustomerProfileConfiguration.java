package vn.viettel.okrkpi.policy.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.policy.application.CustomerProfileService;
import vn.viettel.okrkpi.policy.application.PolicyApprovalService;
import vn.viettel.okrkpi.policy.application.PolicyResolutionService;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackSigner;
import vn.viettel.okrkpi.shared.kernel.Clock;

/**
 * CORE-05-12: hồ sơ tập khách hàng, khung tham số DC-02 và phân giải hồ sơ ghim cùng gắn của kỳ.
 * Kiểm khung tham số được nối vào bước gửi phát hành của {@link PolicyApprovalService}.
 */
@Configuration(proxyBeanMethods = false)
public class CustomerProfileConfiguration {

  @Bean
  JdbcCustomerProfileRepository customerProfileRepository(
      @Qualifier("policyDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcCustomerProfileRepository(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  CustomerProfileService customerProfileService(
      JdbcCustomerProfileRepository customerProfileRepository,
      JdbcPolicyPackRepository policyPackRepository,
      JdbcPolicyResolutionRepository policyResolutionRepository,
      ObjectProvider<PolicyResolutionService> policyResolutionService,
      PolicyPackSigner policyPackSigner,
      PolicyAudit policyAudit,
      ObjectProvider<ObjectMapper> json,
      ObjectProvider<PolicyApprovalService> policyApprovalService,
      @Value("${mission.policy.resolution-cache.ttl:5m}") Duration ttl) {
    CustomerProfileService service =
        new CustomerProfileService(
            customerProfileRepository,
            policyPackRepository,
            policyResolutionRepository,
            policyResolutionService::getIfAvailable,
            policyPackSigner,
            policyAudit,
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc(),
            ttl);
    policyApprovalService.ifAvailable(a -> a.setPublishGuard(service::checkInheritance));
    return service;
  }
}
