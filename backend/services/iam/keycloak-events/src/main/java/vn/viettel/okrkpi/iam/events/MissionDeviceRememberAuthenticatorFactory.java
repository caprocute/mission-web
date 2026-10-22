package vn.viettel.okrkpi.iam.events;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/** Nhà máy của bước {@value #ID} (DV-02-03), bước cuối của luồng đăng nhập ghi thiết bị và vị trí đã biết. */
public class MissionDeviceRememberAuthenticatorFactory implements AuthenticatorFactory {

  public static final String ID = "mission-device-remember";
  private static final MissionDeviceRememberAuthenticator INSTANCE = new MissionDeviceRememberAuthenticator();

  @Override
  public Authenticator create(KeycloakSession session) {
    return INSTANCE;
  }

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public String getDisplayType() {
    return "Mission: ghi nhận thiết bị đã xác thực";
  }

  @Override
  public String getReferenceCategory() {
    return null;
  }

  @Override
  public boolean isConfigurable() {
    return false;
  }

  @Override
  public Requirement[] getRequirementChoices() {
    return new Requirement[] {Requirement.REQUIRED, Requirement.DISABLED};
  }

  @Override
  public boolean isUserSetupAllowed() {
    return false;
  }

  @Override
  public String getHelpText() {
    return "Ghi thiết bị và dải mạng của lần đăng nhập thành công (BR-00-06).";
  }

  @Override
  public List<ProviderConfigProperty> getConfigProperties() {
    return List.of();
  }

  @Override
  public void init(Config.Scope config) {
  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {
  }

  @Override
  public void close() {
  }
}
