package vn.viettel.okrkpi.iam.events;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/** Nhà máy của bước {@value #ID} (DV-02-08). */
public class RecoveryCodeAuthenticatorFactory implements AuthenticatorFactory {

  public static final String ID = "mission-recovery-code";
  private static final RecoveryCodeAuthenticator INSTANCE = new RecoveryCodeAuthenticator();

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
    return "Mã khôi phục";
  }

  @Override
  public String getReferenceCategory() {
    return RecoveryCodeStore.TYPE;
  }

  @Override
  public boolean isConfigurable() {
    return false;
  }

  @Override
  public Requirement[] getRequirementChoices() {
    return new Requirement[] {Requirement.ALTERNATIVE, Requirement.DISABLED};
  }

  @Override
  public boolean isUserSetupAllowed() {
    return false;
  }

  @Override
  public String getHelpText() {
    return "Nhập mã khôi phục một lần do quản trị đơn vị cấp khi mất thiết bị SmartOTP.";
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
