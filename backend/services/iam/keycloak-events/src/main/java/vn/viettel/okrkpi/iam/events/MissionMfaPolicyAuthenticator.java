package vn.viettel.okrkpi.iam.events;

import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.NewCookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserLoginFailureModel;
import org.keycloak.models.UserModel;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * DV-02-03 · QT-00 bước 4 · Bước "Đánh giá yêu cầu MFA" của luồng {@code mission-browser}, đặt sau bước bậc 1 và
 * trước nhánh điều kiện bậc 2. Khi {@link MfaPolicy} kết luận bắt buộc MFA, bước này nâng mức yêu cầu của phiên xác
 * thực lên bậc 2, nên nhánh bậc 2 (SmartOTP hoặc WebAuthn) chạy và token mang {@code acr=aal2}; không có nhánh nào bỏ
 * qua được bước này (TL-04, sửa S06).
 *
 * <p>Thiết bị nhận diện bằng cookie {@value #COOKIE} mang mã ngẫu nhiên do realm ký (khoá của realm, không có bí mật
 * riêng) cùng dấu vân tay thô là băm SHA-256 của User-Agent; vị trí là dải mạng của địa chỉ kết nối. Giá trị đã biết
 * chỉ được ghi sau khi cả luồng thành công, ở bước {@link MissionDeviceRememberAuthenticator}. Không ghi mã OTP, cookie
 * hay địa chỉ IP vào nhật ký.
 */
final class MissionMfaPolicyAuthenticator implements Authenticator {

  static final String COOKIE = "MISSION_DEVICE";
  static final String NETWORKS_ATTRIBUTE = "mission_known_networks";
  static final String NOTE_DEVICES = "mission.remember.devices";
  static final String NOTE_NETWORK = "mission.remember.network";
  static final String DETAIL_REASONS = "mission_mfa_reasons";
  static final String DETAIL_NEW_DEVICE = "mission_device_new";
  static final String TENANT_MFA_ATTRIBUTE = "mission.mfa_all_users";
  private static final int COOKIE_DAYS = 400;
  private static final String COOKIE_TYPE = "mission-device";
  private static final Logger LOG = Logger.getLogger(MissionMfaPolicyAuthenticator.class);

  @Override
  public void authenticate(AuthenticationFlowContext context) {
    UserModel user = context.getUser();
    RealmModel realm = context.getRealm();
    KeycloakSession session = context.getSession();
    MfaPolicy.Settings settings = MissionMfaPolicyAuthenticatorFactory.settings(context.getAuthenticatorConfig());

    Set<String> mfaRoles = settings.roles().stream().filter(r -> hasRole(realm, user, r))
        .collect(Collectors.toCollection(LinkedHashSet::new));
    String fingerprint = MissionEventListenerProvider.deviceKey(
        context.getHttpRequest().getHttpHeaders().getHeaderString("User-Agent"));
    String deviceId = deviceIdFromCookie(context);
    List<String> presented = new ArrayList<>();
    presented.add(fingerprint);
    if (deviceId != null) {
      presented.add(cookieKey(deviceId));
    } else {
      deviceId = UUID.randomUUID().toString();
      issueCookie(context, deviceId);
    }
    String network = MfaPolicy.network(context.getConnection().getRemoteAddr());
    UserLoginFailureModel failure = session.loginFailures().getUserLoginFailure(realm, user.getId());

    MfaPolicy.Decision d = MfaPolicy.evaluate(settings, new MfaPolicy.Input(mfaRoles, tenantRequiresMfa(session, user),
        user.getAttributeStream(MissionEventListenerProvider.DEVICES_ATTRIBUTE).toList(), presented,
        user.getAttributeStream(NETWORKS_ATTRIBUTE).toList(), network,
        failure == null ? 0 : failure.getNumFailures()));

    AuthenticationSessionModel auth = context.getAuthenticationSession();
    auth.setAuthNote(NOTE_DEVICES, fingerprint + "," + cookieKey(deviceId));
    if (network != null) {
      auth.setAuthNote(NOTE_NETWORK, network);
    }
    context.getEvent().detail(DETAIL_NEW_DEVICE, String.valueOf(d.newDevice() || d.newNetwork()));
    if (d.required()) {
      context.getEvent().detail(DETAIL_REASONS, String.join(",", d.reasons()));
      String requested = auth.getClientNote(Constants.REQUESTED_LEVEL_OF_AUTHENTICATION);
      if (parse(requested) < 2) {
        auth.setClientNote(Constants.REQUESTED_LEVEL_OF_AUTHENTICATION, "2");
      }
    }
    context.success();
  }

  @Override
  public void action(AuthenticationFlowContext context) {
    context.success();
  }

  @Override
  public boolean requiresUser() {
    return true;
  }

  @Override
  public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
    return true;
  }

  @Override
  public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
  }

  @Override
  public void close() {
  }

  private static boolean hasRole(RealmModel realm, UserModel user, String name) {
    RoleModel role = realm.getRole(name);
    return role != null && user.hasRole(role);
  }

  /** Tenant (Organization mà người dùng là thành viên) bật MFA cho mọi người dùng, mặc định cho hệ thống cấp độ 4. */
  private static boolean tenantRequiresMfa(KeycloakSession session, UserModel user) {
    try {
      OrganizationProvider orgs = session.getProvider(OrganizationProvider.class);
      if (orgs == null || !orgs.isEnabled()) {
        return false;
      }
      return orgs.getByMember(user).map(o -> o.getAttributes() == null ? null : o.getAttributes().get(TENANT_MFA_ATTRIBUTE))
          .filter(Objects::nonNull).anyMatch(v -> v.contains("true"));
    } catch (RuntimeException e) {
      // Không đọc được tenant thì không nới lỏng gì: vai trò và rủi ro vẫn được xét.
      LOG.warnf("Không đọc được cấu hình MFA của tenant: %s", e.getClass().getSimpleName());
      return false;
    }
  }

  private static String deviceIdFromCookie(AuthenticationFlowContext context) {
    Map<String, Cookie> cookies = context.getHttpRequest().getHttpHeaders().getCookies();
    Cookie c = cookies == null ? null : cookies.get(COOKIE);
    if (c == null || c.getValue() == null || c.getValue().isBlank()) {
      return null;
    }
    try {
      JsonWebToken t = context.getSession().tokens().decode(c.getValue(), JsonWebToken.class);
      return t != null && t.isActive() && COOKIE_TYPE.equals(t.getType()) ? t.getId() : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static void issueCookie(AuthenticationFlowContext context, String deviceId) {
    try {
      JsonWebToken t = new JsonWebToken().id(deviceId).type(COOKIE_TYPE).issuedNow();
      t.exp(t.getIat() + COOKIE_DAYS * 86400L);
      String value = context.getSession().tokens().encode(t);
      String path = context.getHttpRequest().getUri().getBaseUri().getPath() + "realms/" + context.getRealm().getName()
          + "/";
      context.getSession().getContext().getHttpResponse().setCookieIfAbsent(new NewCookie.Builder(COOKIE).value(value)
          .path(path).maxAge(COOKIE_DAYS * 86400).httpOnly(true).secure(true)
          .sameSite(NewCookie.SameSite.LAX).build());
    } catch (RuntimeException e) {
      // Không cấp được cookie thì còn dấu vân tay thô.
      LOG.warnf("Không cấp được cookie thiết bị: %s", e.getClass().getSimpleName());
    }
  }

  static String cookieKey(String deviceId) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(deviceId.getBytes(StandardCharsets.UTF_8));
      return "c:" + HexFormat.of().formatHex(h, 0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int parse(String level) {
    try {
      return level == null ? 0 : Integer.parseInt(level);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
