package vn.viettel.okrkpi.config.infrastructure.client;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.config.application.port.RealmRoles;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;

/** {@link RealmRoles} trên client Admin API Keycloak dùng chung (tài khoản dịch vụ, bí mật ESO). */
public class KeycloakRealmRoles implements RealmRoles {

  private final KeycloakAdminClient keycloak;

  public KeycloakRealmRoles(KeycloakAdminClient keycloak) {
    this.keycloak = Objects.requireNonNull(keycloak, "keycloak");
  }

  @Override
  public List<String> rolesOf(UUID userId) {
    return keycloak.realmRolesOf(userId);
  }

  @Override
  public void add(UUID userId, String role) {
    keycloak.addRealmRole(userId, role);
  }

  @Override
  public void remove(UUID userId, String role) {
    keycloak.removeRealmRole(userId, role);
  }
}
