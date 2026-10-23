package vn.viettel.okrkpi.policy.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.policy.application.WorkflowBindingService;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.WorkflowBindings;
import vn.viettel.okrkpi.policy.infrastructure.client.WorkflowBindingClient;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/**
 * CORE-05-11: lớp công bố cấu hình luồng duyệt gọi SVC-06 tại {@code
 * mission.policy.workflow.base-url} (mặc định dùng chung {@code mission.config.workflow.base-url}
 * của saga chốt kỳ). Chưa cấu hình thì mọi thao tác trả 503 kèm {@code Retry-After}, không bao giờ
 * trả dữ liệu cũ.
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowBindingConfiguration {

  @Bean
  WorkflowBindings workflowBindings(
      ObjectProvider<ResilientClientFactory> factory,
      ObjectProvider<KeycloakAdminClient> keycloak,
      @Value("${mission.policy.workflow.base-url:${mission.config.workflow.base-url:}}")
          String baseUrl) {
    ResilientClientFactory f = factory.getIfAvailable();
    if (baseUrl == null || baseUrl.isBlank() || f == null) {
      return new Unconfigured();
    }
    return new WorkflowBindingClient(
        f.create(WorkflowBindingClient.INSTANCE, baseUrl),
        () -> {
          KeycloakAdminClient k = keycloak.getIfAvailable();
          if (k == null) {
            throw new DependencyUnavailableException(
                "keycloak", DependencyUnavailableException.Reason.IO_ERROR, null, null);
          }
          return k.serviceToken();
        });
  }

  @Bean
  WorkflowBindingService workflowBindingService(
      JdbcPolicyPackRepository policyPackRepository,
      WorkflowBindings workflowBindings,
      ObjectProvider<PeriodApi> periodApi,
      PolicyAudit policyAudit,
      ObjectProvider<ObjectMapper> json) {
    return new WorkflowBindingService(
        policyPackRepository,
        workflowBindings,
        periodApi::getIfAvailable,
        policyAudit,
        json.getIfAvailable(ObjectMapper::new));
  }

  /** SVC-06 chưa cấu hình: 503 để người soạn thử lại sau. */
  static final class Unconfigured implements WorkflowBindings {

    private static DependencyUnavailableException unavailable() {
      return new DependencyUnavailableException(
          WorkflowBindingClient.INSTANCE,
          DependencyUnavailableException.Reason.IO_ERROR,
          Duration.ofSeconds(30),
          null);
    }

    @Override
    public List<Map<String, Object>> list(TenantId tenant, UUID packVersionId) {
      throw unavailable();
    }

    @Override
    public Optional<Versioned> find(TenantId tenant, UUID packVersionId, String processKind) {
      throw unavailable();
    }

    @Override
    public Versioned save(
        TenantId tenant,
        UUID packVersionId,
        String processKind,
        long expectedRowVersion,
        String idempotencyKey,
        Map<String, Object> binding) {
      throw unavailable();
    }

    @Override
    public Map<String, Object> simulate(
        TenantId tenant, Map<String, Object> request, String idempotencyKey) {
      throw unavailable();
    }

    @Override
    public Map<String, Object> templates(
        TenantId tenant, String processKind, String q, int limit, String cursor) {
      throw unavailable();
    }

    @Override
    public Optional<Versioned> template(TenantId tenant, String templateKey, int versionNo) {
      throw unavailable();
    }
  }
}
