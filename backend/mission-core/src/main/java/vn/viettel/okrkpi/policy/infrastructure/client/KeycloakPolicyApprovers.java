package vn.viettel.okrkpi.policy.infrastructure.client;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import vn.viettel.okrkpi.policy.application.port.PolicyApprovers;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Người giữ vai trò realm {@code decider} (AC-08, ma trận phân quyền SCR-1503.ky-duyet) của tenant
 * qua Admin API Keycloak. Chưa cấu hình Admin API hoặc gọi lỗi thì trả danh sách rỗng: không gửi
 * được thông báo nhưng không chặn việc gửi phê duyệt.
 */
public class KeycloakPolicyApprovers implements PolicyApprovers {

  static final String ROLE = "decider";
  private static final Logger log = LoggerFactory.getLogger(KeycloakPolicyApprovers.class);

  private final ObjectProvider<KeycloakAdminClient> keycloak;

  public KeycloakPolicyApprovers(ObjectProvider<KeycloakAdminClient> keycloak) {
    this.keycloak = Objects.requireNonNull(keycloak, "keycloak");
  }

  @Override
  public List<UUID> approvers(TenantId tenant) {
    KeycloakAdminClient client = keycloak.getIfAvailable();
    if (client == null) {
      return List.of();
    }
    try {
      return client.roleHolders(tenant, ROLE);
    } catch (RuntimeException e) {
      log.warn("Không đọc được người phê duyệt Policy Pack của tenant {}", tenant.value(), e);
      return List.of();
    }
  }
}
