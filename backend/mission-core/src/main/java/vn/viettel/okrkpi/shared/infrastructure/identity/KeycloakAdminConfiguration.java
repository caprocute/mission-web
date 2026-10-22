package vn.viettel.okrkpi.shared.infrastructure.identity;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Client Admin API Keycloak của cell, bật khi khai {@code mission.config.keycloak-admin.base-url}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mission.config.keycloak-admin", name = "base-url")
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class KeycloakAdminConfiguration {

  @Bean
  KeycloakAdminClient keycloakAdminClient(
      ObjectProvider<RestClient.Builder> builder, KeycloakAdminProperties props) {
    return new KeycloakAdminClient(
        builder.getIfAvailable(RestClient::builder), props, Clock.systemUTC());
  }
}
