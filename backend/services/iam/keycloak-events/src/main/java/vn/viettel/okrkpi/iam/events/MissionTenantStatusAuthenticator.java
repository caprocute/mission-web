package vn.viettel.okrkpi.iam.events;

import jakarta.ws.rs.core.Response;
import java.util.Set;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

/**
 * Bước "Kiểm trạng thái tenant" của các luồng đăng nhập (TL-04 mục 11.4, TL-02a QT-01): người dùng thuộc tenant đang
 * {@code TAM_NGUNG} hoặc {@code DA_CHAM_DUT} không đăng nhập được và thấy MSG-00-07; dữ liệu của tenant giữ nguyên.
 * Quản trị nền tảng (AC-01, vai trò {@value #PLATFORM_ADMIN}) vẫn đăng nhập được để xử lý và mở lại tenant, như mô tả
 * của {@code suspendConfigTenant}. Tenant của người dùng lấy từ thuộc tính {@code tenant_id} (cùng nguồn với claim
 * của {@link MissionContextMapper}); người không có thuộc tính này không thuộc tenant nào và đi tiếp.
 */
final class MissionTenantStatusAuthenticator implements Authenticator {

  static final String PLATFORM_ADMIN = "platform_admin";
  static final Set<String> BLOCKED = Set.of("TAM_NGUNG", "DA_CHAM_DUT");
  static final String MSG_00_07 = "Tổ chức của đồng chí đang tạm ngừng sử dụng nền tảng.";
  static final String EVENT_ERROR = "tenant_suspended";
  private static final Logger LOG = Logger.getLogger(MissionTenantStatusAuthenticator.class);

  private final TenantStatusLookup lookup;

  MissionTenantStatusAuthenticator(TenantStatusLookup lookup) {
    this.lookup = lookup;
  }

  @Override
  public void authenticate(AuthenticationFlowContext context) {
    UserModel user = context.getUser();
    if (user == null || isPlatformAdmin(context.getRealm(), user)) {
      context.success();
      return;
    }
    String tenant = user.getFirstAttribute(MissionContextMapper.TENANT);
    String status = lookup.status(tenant).orElse(null);
    if (status != null && BLOCKED.contains(status)) {
      LOG.infof("Chặn đăng nhập của người dùng %s: tenant %s đang %s", user.getId(), tenant, status);
      context.getEvent().user(user).detail("tenant_status", status).error(EVENT_ERROR);
      Response page = context.form().setError(MSG_00_07).createErrorPage(Response.Status.FORBIDDEN);
      context.failure(AuthenticationFlowError.USER_DISABLED, page);
      return;
    }
    context.success();
  }

  private static boolean isPlatformAdmin(RealmModel realm, UserModel user) {
    RoleModel role = realm.getRole(PLATFORM_ADMIN);
    return role != null && user.hasRole(role);
  }

  @Override
  public void action(AuthenticationFlowContext context) {
    authenticate(context);
  }

  @Override
  public boolean requiresUser() {
    return false;
  }

  @Override
  public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
    return true;
  }

  @Override
  public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {}

  @Override
  public void close() {}
}
