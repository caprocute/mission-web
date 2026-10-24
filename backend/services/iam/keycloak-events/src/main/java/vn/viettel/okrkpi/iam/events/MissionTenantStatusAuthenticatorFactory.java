package vn.viettel.okrkpi.iam.events;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * Nhà máy của bước {@value #ID}: chặn đăng nhập của người dùng thuộc tenant tạm ngừng hoặc đã chấm dứt (TL-04 mục
 * 11.4). Không có cấu hình trong luồng; địa chỉ tenant-registry và tài khoản dịch vụ đọc từ biến môi trường (xem
 * {@link TenantStatusLookup}).
 */
public class MissionTenantStatusAuthenticatorFactory implements AuthenticatorFactory {

  public static final String ID = "mission-tenant-status";
  private MissionTenantStatusAuthenticator instance;

  @Override
  public Authenticator create(KeycloakSession session) {
    return instance;
  }

  @Override
  public void init(Config.Scope config) {
    instance = new MissionTenantStatusAuthenticator(TenantStatusLookup.fromEnv());
  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {}

  @Override
  public void close() {}

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public String getDisplayType() {
    return "Mission: kiểm trạng thái tenant";
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
    return "Từ chối đăng nhập khi tenant của người dùng đang tạm ngừng hoặc đã chấm dứt; quản trị nền tảng vẫn vào được.";
  }

  @Override
  public List<ProviderConfigProperty> getConfigProperties() {
    return List.of();
  }
}
