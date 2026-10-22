package vn.viettel.okrkpi.iam.events;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticator;
import org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.OTPCredentialModel;
import org.keycloak.models.credential.WebAuthnCredentialModel;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * DV-02-03 · Điều kiện {@value #ID}: người dùng chưa có yếu tố thứ hai nào (SmartOTP hay WebAuthn). Nhánh bậc 2 dùng
 * điều kiện này để bắt người chưa cài phải cài SmartOTP ngay trong lần đăng nhập, thay vì để luồng thất bại hoặc bỏ
 * qua MFA (TL-04, sửa S06).
 */
public class MissionNoSecondFactorConditionFactory implements ConditionalAuthenticatorFactory {

  public static final String ID = "mission-condition-no-second-factor";

  static final ConditionalAuthenticator CONDITION = new ConditionalAuthenticator() {
    @Override
    public boolean matchCondition(AuthenticationFlowContext context) {
      UserModel user = context.getUser();
      return user != null && !user.credentialManager().isConfiguredFor(OTPCredentialModel.TYPE)
          && !user.credentialManager().isConfiguredFor(WebAuthnCredentialModel.TYPE_TWOFACTOR);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
    }

    @Override
    public boolean requiresUser() {
      return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
  };

  @Override
  public ConditionalAuthenticator getSingleton() {
    return CONDITION;
  }

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public String getDisplayType() {
    return "Mission: điều kiện chưa có yếu tố thứ hai";
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
    return "Đúng khi người dùng chưa cài SmartOTP hay WebAuthn.";
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
