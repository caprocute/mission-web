package vn.viettel.okrkpi.shared.infrastructure.e2ee;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Client SVC-04 đọc khoá phục hồi, bật khi khai {@code mission.e2ee.org.base-url}; token dịch vụ
 * lấy từ client Keycloak của cell (client-credentials).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mission.e2ee.org", name = "base-url")
public class OrgRecoveryKeyConfiguration {

  @Bean
  OrgRecoveryKeyClient orgRecoveryKeyClient(
      ObjectProvider<RestClient.Builder> builder,
      ObjectProvider<KeycloakAdminClient> keycloak,
      @Value("${mission.e2ee.org.base-url}") String baseUrl) {
    return new OrgRecoveryKeyClient(
        builder.getIfAvailable(RestClient::builder),
        baseUrl,
        () -> {
          KeycloakAdminClient k = keycloak.getIfAvailable();
          if (k == null) {
            throw new DependencyUnavailableException(
                "keycloak", DependencyUnavailableException.Reason.IO_ERROR, null, null);
          }
          return k.serviceToken();
        });
  }
}
