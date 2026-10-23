package vn.viettel.okrkpi.iam.vneid;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/** Nhà máy của bước {@value #ID} (DV-02-04): gắn danh tính VNeID vào người đã khớp và đặt bậc xác thực 2. */
public class VneidLinkAuthenticatorFactory implements AuthenticatorFactory {

  public static final String ID = "mission-vneid-link";
  private static final VneidLinkAuthenticator INSTANCE = new VneidLinkAuthenticator();

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
    return "Mission: liên kết danh tính VNeID";
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
    return "Gắn danh tính VNeID vào người đã khớp theo số định danh, không tạo tài khoản mới (BR-00-04).";
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
