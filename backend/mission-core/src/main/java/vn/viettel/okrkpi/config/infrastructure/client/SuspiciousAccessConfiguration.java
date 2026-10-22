package vn.viettel.okrkpi.config.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.config.application.SuspiciousAccessService;
import vn.viettel.okrkpi.config.infrastructure.persistence.OutboxSecurityAlerts;
import vn.viettel.okrkpi.config.infrastructure.web.SuspiciousAccessController;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;

/**
 * DV-02-05: báo truy cập bất thường. Bật khi cell khai {@code
 * mission.config.keycloak-admin.base-url}; outbox ghi {@code config.outbox_event} qua DataSource và
 * giao dịch của MOD-08.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mission.config.keycloak-admin", name = "base-url")
public class SuspiciousAccessConfiguration {

  @Bean
  KeycloakIdentityAdmin keycloakIdentityAdmin(KeycloakAdminClient keycloak) {
    return new KeycloakIdentityAdmin(keycloak);
  }

  @Bean
  OutboxSecurityAlerts configSecurityAlerts(
      @Qualifier("configDataSource") DataSource dataSource,
      @Value("${mission.config.outbox.registry-url:${okrkpi.inbox.registry.url}}") String registry,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxSecurityAlerts(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "config",
            new ApicurioPayloadSerializer(registry, Map.of()),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  SuspiciousAccessService suspiciousAccessService(
      KeycloakIdentityAdmin identity,
      OutboxSecurityAlerts alerts,
      AuditLogger audit,
      @Qualifier("configTransactionManager") PlatformTransactionManager tm) {
    return new SuspiciousAccessService(
        identity, alerts, audit, new TransactionTemplate(tm), Clock.systemUtc());
  }

  @Bean
  SuspiciousAccessController suspiciousAccessController(
      SuspiciousAccessService service, ObjectProvider<AuthzSubjectResolver> subjects) {
    return new SuspiciousAccessController(service, subjects);
  }
}
