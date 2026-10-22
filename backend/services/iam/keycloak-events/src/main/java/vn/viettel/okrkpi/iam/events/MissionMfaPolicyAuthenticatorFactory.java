package vn.viettel.okrkpi.iam.events;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * Nhà máy của bước {@value #ID} (DV-02-03). Cấu hình qua authenticator config của luồng trong mẫu realm: danh sách vai
 * trò bắt buộc bậc 2, trọng số các dấu hiệu rủi ro và ngưỡng (xem {@link MfaPolicy}).
 */
public class MissionMfaPolicyAuthenticatorFactory implements AuthenticatorFactory {

  public static final String ID = "mission-mfa-policy";
  static final String ROLES = "roles";
  static final String W_DEVICE = "weightNewDevice";
  static final String W_NETWORK = "weightNewNetwork";
  static final String W_FAILURES = "weightFailures";
  static final String THRESHOLD = "threshold";
  private static final MissionMfaPolicyAuthenticator INSTANCE = new MissionMfaPolicyAuthenticator();

  static MfaPolicy.Settings settings(AuthenticatorConfigModel model) {
    MfaPolicy.Settings d = MfaPolicy.Settings.defaults();
    Map<String, String> c = model == null || model.getConfig() == null ? Map.of() : model.getConfig();
    String roles = c.get(ROLES);
    List<String> list = roles == null || roles.isBlank() ? d.roles()
        : Arrays.stream(roles.split("[,#\\s]+")).filter(s -> !s.isBlank()).toList();
    return new MfaPolicy.Settings(list, number(c.get(W_DEVICE), d.weightNewDevice()),
        number(c.get(W_NETWORK), d.weightNewNetwork()), number(c.get(W_FAILURES), d.weightFailures()),
        number(c.get(THRESHOLD), d.threshold()));
  }

  private static int number(String v, int fallback) {
    try {
      return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

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
    return "Mission: đánh giá yêu cầu MFA";
  }

  @Override
  public String getReferenceCategory() {
    return null;
  }

  @Override
  public boolean isConfigurable() {
    return true;
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
    return "Nâng yêu cầu lên bậc 2 theo vai trò, cấu hình tenant, thiết bị và vị trí mới (QT-00 bước 4).";
  }

  @Override
  public List<ProviderConfigProperty> getConfigProperties() {
    MfaPolicy.Settings d = MfaPolicy.Settings.defaults();
    return List.of(
        property(ROLES, "Vai trò bắt buộc bậc 2", "Phân tách bằng dấu phẩy.", String.join(",", d.roles())),
        property(W_DEVICE, "Trọng số thiết bị mới", null, String.valueOf(d.weightNewDevice())),
        property(W_NETWORK, "Trọng số vị trí mới", null, String.valueOf(d.weightNewNetwork())),
        property(W_FAILURES, "Trọng số có lần đăng nhập sai", null, String.valueOf(d.weightFailures())),
        property(THRESHOLD, "Ngưỡng điểm rủi ro", null, String.valueOf(d.threshold())));
  }

  private static ProviderConfigProperty property(String name, String label, String help, String def) {
    ProviderConfigProperty p = new ProviderConfigProperty(name, label, help, ProviderConfigProperty.STRING_TYPE, def);
    return p;
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
