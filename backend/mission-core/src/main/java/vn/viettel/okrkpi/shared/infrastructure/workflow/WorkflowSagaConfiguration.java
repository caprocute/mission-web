package vn.viettel.okrkpi.shared.infrastructure.workflow;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Client SVC-06, bật khi khai {@code mission.config.workflow.base-url}; token dịch vụ lấy từ client
 * Keycloak của cell (client-credentials).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mission.config.workflow", name = "base-url")
public class WorkflowSagaConfiguration {

  @Bean
  WorkflowSagaClient workflowSagaClient(
      ObjectProvider<RestClient.Builder> builder,
      ObjectProvider<KeycloakAdminClient> keycloak,
      @Value("${mission.config.workflow.base-url}") String baseUrl) {
    return new WorkflowSagaClient(
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
