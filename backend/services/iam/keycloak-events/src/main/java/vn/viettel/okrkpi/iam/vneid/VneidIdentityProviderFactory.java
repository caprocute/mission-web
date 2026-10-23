package vn.viettel.okrkpi.iam.vneid;

import org.keycloak.Config;
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.broker.oidc.OIDCIdentityProviderFactory;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;

/** Nhà máy của nhà cung cấp danh tính {@value #ID} (DV-02-04); tham số triển khai đọc một lần lúc Keycloak khởi động. */
public class VneidIdentityProviderFactory extends OIDCIdentityProviderFactory {

  public static final String ID = "mission-vneid";

  private VneidSettings settings;
  private PersonMatcher persons;

  @Override
  public String getName() {
    return "VNeID (Mission)";
  }

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public void init(Config.Scope config) {
    settings = VneidSettings.fromEnv();
    persons = new OrgServicePersonMatcher(settings);
  }

  @Override
  public OIDCIdentityProvider create(KeycloakSession session, IdentityProviderModel model) {
    if (settings == null) {
      init(null);
    }
    return new VneidIdentityProvider(session, new OIDCIdentityProviderConfig(model), settings, persons);
  }
}
