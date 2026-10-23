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

  /** CORE-02-08: cầu nối luồng duyệt, cùng địa chỉ và token dịch vụ với client saga. */
  @Bean
  WorkflowApprovalClient workflowApprovalClient(
      ObjectProvider<RestClient.Builder> builder,
      ObjectProvider<KeycloakAdminClient> keycloak,
      @Value("${mission.config.workflow.base-url}") String baseUrl) {
    return new WorkflowApprovalClient(
        builder.getIfAvailable(RestClient::builder), baseUrl, () -> serviceToken(keycloak));
  }

  /** CORE-02-08: hộp việc của người dùng (token của người dùng, không dùng token dịch vụ). */
  @Bean
  WorkflowInboxClient workflowInboxClient(
      ObjectProvider<RestClient.Builder> builder,
      @Value("${mission.config.workflow.base-url}") String baseUrl) {
    return new WorkflowInboxClient(builder.getIfAvailable(RestClient::builder), baseUrl);
  }

  @Bean
  WorkflowSagaClient workflowSagaClient(
      ObjectProvider<RestClient.Builder> builder,
      ObjectProvider<KeycloakAdminClient> keycloak,
      @Value("${mission.config.workflow.base-url}") String baseUrl) {
    return new WorkflowSagaClient(
        builder.getIfAvailable(RestClient::builder), baseUrl, () -> serviceToken(keycloak));
  }

  private static String serviceToken(ObjectProvider<KeycloakAdminClient> keycloak) {
    KeycloakAdminClient k = keycloak.getIfAvailable();
    if (k == null) {
      throw new DependencyUnavailableException(
          "keycloak", DependencyUnavailableException.Reason.IO_ERROR, null, null);
    }
    return k.serviceToken();
  }
}
