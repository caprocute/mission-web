package vn.viettel.okrkpi.config.infrastructure.client;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.config.application.port.IdentityAdmin;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@link IdentityAdmin} của MOD-08 trên client Admin API Keycloak dùng chung. */
public class KeycloakIdentityAdmin implements IdentityAdmin {

  private final KeycloakAdminClient keycloak;

  public KeycloakIdentityAdmin(KeycloakAdminClient keycloak) {
    this.keycloak = Objects.requireNonNull(keycloak, "keycloak");
  }

  @Override
  public int revokeAllSessions(UUID userId) {
    return keycloak.revokeAllSessions(userId);
  }

  @Override
  public void requirePasswordChange(UUID userId) {
    keycloak.requirePasswordChange(userId);
  }

  @Override
  public List<UUID> tenantAdmins(TenantId tenant) {
    return keycloak.tenantAdmins(tenant);
  }

  @Override
  public String passwordChangeUrl() {
    return keycloak.passwordChangeUrl();
  }
}
