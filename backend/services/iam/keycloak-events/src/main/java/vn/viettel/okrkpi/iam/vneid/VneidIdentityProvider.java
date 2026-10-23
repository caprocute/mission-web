package vn.viettel.okrkpi.iam.vneid;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.http.client.config.RequestConfig;
import org.jboss.logging.Logger;
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.events.Details;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.http.simple.SimpleHttp;
import org.keycloak.http.simple.SimpleHttpRequest;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.protocol.oidc.endpoints.AuthorizationEndpoint;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.representations.JsonWebToken;
import org.keycloak.services.ErrorPage;
import org.keycloak.services.messages.Messages;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Nhà cung cấp danh tính VNeID (DV-02-04 · QT-00 bước 3, ADR-08): OIDC Authorization Code + PKCE S256, Keycloak tự
 * kiểm {@code state} và {@code nonce} như mọi nhà cung cấp OIDC (khắc phục L03). Phần riêng của Mission:
 *
 * <ol>
 *   <li>Chỉ nhận tài khoản đạt mức tối thiểu ({@code missionRequiredLevel}, mặc định 2) theo claim {@code
 *       missionLevelClaim}; mức thấp hơn dừng với MSG-00-03 (BR-00-03).
 *   <li>Số định danh (claim {@code missionNationalIdClaim}) chỉ dùng để hỏi org-service người nào của tenant khớp; claim
 *       bị xoá khỏi mã thông báo đã kiểm trước khi Keycloak lưu ngữ cảnh môi giới, nên không vào phiên, bảng hay nhật
 *       ký. Mã chủ thể VNeID được băm HMAC trước khi thành mã liên kết.
 *   <li>Khớp đúng một người và người đó chưa gắn VNeID khác thì cho luồng liên kết tiếp tục; không khớp thì dừng,
 *       không tạo tài khoản; liên kết trùng bị chặn và ghi sự kiện kiểm toán (BR-00-04).
 *   <li>Lời gọi VNeID có thời hạn ({@code missionTimeoutMillis}, mặc định 10 giây) và bộ ngắt mạch; mạch mở hoặc
 *       VNeID lỗi thì trang đăng nhập hiện MSG-00-04.
 * </ol>
 *
 * Tenant lấy từ tham số yêu cầu xác thực {@code tenant_id} mà ứng dụng gửi kèm (sau {@code resolveTenantCell}); tham
 * số này chỉ chọn tenant để so khớp, quyền vẫn do bản ghi người của tenant quyết định.
 */
public class VneidIdentityProvider extends OIDCIdentityProvider {

  private static final Logger LOG = Logger.getLogger(VneidIdentityProvider.class);

  public static final String LEVEL_CLAIM = "missionLevelClaim";
  public static final String NATIONAL_ID_CLAIM = "missionNationalIdClaim";
  public static final String REQUIRED_LEVEL = "missionRequiredLevel";
  public static final String TENANT_PARAM = "missionTenantParam";
  public static final String TIMEOUT_MILLIS = "missionTimeoutMillis";
  public static final String BREAKER_FAILURES = "missionBreakerFailures";
  public static final String BREAKER_OPEN_SECONDS = "missionBreakerOpenSeconds";

  /** Khoá ngữ cảnh môi giới mang mã người đã khớp sang luồng đăng nhập lần đầu. */
  static final String PERSON_NOTE = "mission.vneid.person";
  private static final String LEVEL_NOTE = "mission.vneid.level";
  private static final String NATIONAL_ID_NOTE = "mission.vneid.nid";

  static final String MSG_LEVEL = "vneidLevelMessage";
  static final String MSG_UNAVAILABLE = "vneidUnavailableMessage";
  static final String MSG_NOT_LINKED = "vneidNotLinkedMessage";
  static final String MSG_ALREADY_LINKED = "vneidAlreadyLinkedMessage";

  /** Đánh dấu VNeID đã trả lời ở điểm cuối token trong lượt gọi lại hiện tại (phân loại lỗi cho bộ ngắt). */
  private static final ThreadLocal<Boolean> TOKEN_ANSWERED = ThreadLocal.withInitial(() -> false);
  /** Đã gửi yêu cầu tới điểm cuối token của VNeID: state giả mạo bị chặn trước đó nên không làm mở mạch. */
  private static final ThreadLocal<Boolean> TOKEN_ATTEMPTED = ThreadLocal.withInitial(() -> false);

  private final VneidSettings settings;
  private final PersonMatcher persons;

  public VneidIdentityProvider(KeycloakSession session, OIDCIdentityProviderConfig config, VneidSettings settings,
      PersonMatcher persons) {
    super(session, config);
    this.settings = Objects.requireNonNull(settings, "settings");
    this.persons = Objects.requireNonNull(persons, "persons");
  }

  private String cfg(String key, String fallback) {
    String v = getConfig().getConfig().get(key);
    return v == null || v.isBlank() ? fallback : v.trim();
  }

  private int cfgInt(String key, int fallback) {
    try {
      return Integer.parseInt(cfg(key, String.valueOf(fallback)));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  CircuitBreaker breaker() {
    return CircuitBreaker.of(session.getContext().getRealm().getId() + "/" + getConfig().getAlias(),
        cfgInt(BREAKER_FAILURES, 5), cfgInt(BREAKER_OPEN_SECONDS, 30) * 1000L);
  }

  @Override
  public Response performLogin(AuthenticationRequest request) {
    if (!breaker().allow()) {
      LOG.warnf("Bộ ngắt mạch VNeID đang mở (realm %s), không chuyển hướng sang VNeID", request.getRealm().getName());
      return ErrorPage.error(session, request.getAuthenticationSession(), Response.Status.SERVICE_UNAVAILABLE,
          MSG_UNAVAILABLE);
    }
    return super.performLogin(request);
  }

  @Override
  public Object callback(RealmModel realm, AuthenticationCallback callback, EventBuilder event) {
    return new VneidEndpoint(callback, realm, event, this);
  }

  @Override
  public BrokeredIdentityContext getFederatedIdentity(String response) {
    TOKEN_ANSWERED.set(true);
    return super.getFederatedIdentity(response);
  }

  @Override
  protected BrokeredIdentityContext extractIdentity(AccessTokenResponse tokenResponse, String accessToken,
      JsonWebToken idToken) throws IOException {
    BrokeredIdentityContext identity = super.extractIdentity(tokenResponse, accessToken, idToken);
    Map<String, Object> claims = idToken.getOtherClaims();
    String level = VneidIdentity.claim(claims, cfg(LEVEL_CLAIM, "muc_dinh_danh"));
    String nationalIdClaim = cfg(NATIONAL_ID_CLAIM, "so_dinh_danh");
    String nationalId = VneidIdentity.claim(claims, nationalIdClaim);
    // Số định danh không được theo mã thông báo đã kiểm vào ngữ cảnh môi giới (lưu trong phiên xác thực).
    claims.remove(nationalIdClaim);
    if (level != null) {
      identity.getContextData().put(LEVEL_NOTE, level);
    }
    if (nationalId != null) {
      identity.getContextData().put(NATIONAL_ID_NOTE, nationalId);
    }
    return identity;
  }

  @Override
  public void preprocessFederatedIdentity(KeycloakSession session, RealmModel realm, BrokeredIdentityContext context) {
    Object nationalId = context.getContextData().remove(NATIONAL_ID_NOTE);
    Object level = context.getContextData().remove(LEVEL_NOTE);
    super.preprocessFederatedIdentity(session, realm, context);
    AuthenticationSessionModel authSession = context.getAuthenticationSession();
    String alias = getConfig().getAlias();
    if (!VneidIdentity.levelAccepted(level == null ? null : level.toString(), cfgInt(REQUIRED_LEVEL, 2))) {
      audit(session, realm, null, "vneid_level_too_low");
      throw new IdentityBrokerException("Tài khoản VNeID chưa đạt mức yêu cầu").withMessageCode(MSG_LEVEL);
    }
    String hashed;
    try {
      hashed = VneidIdentity.hashedSubject(settings.linkKey(), realm.getName(), context.getId());
    } catch (IllegalStateException e) {
      LOG.error("Chưa cấu hình khoá liên kết danh tính VNeID (MISSION_VNEID_LINK_KEY)");
      throw new IdentityBrokerException("Thiếu khoá liên kết", e)
          .withMessageCode(Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
    }
    context.setId(hashed);
    context.setBrokerUserId(hashed);
    context.setUsername(alias + "." + hashed);
    context.setModelUsername(alias + "." + hashed);

    UserModel person = matchPerson(session, realm, authSession, nationalId == null ? null : nationalId.toString());
    UserModel linked = session.users().getUserByFederatedIdentity(realm,
        new FederatedIdentityModel(alias, hashed, context.getUsername()));
    if (linked != null && !linked.getId().equals(person.getId())) {
      audit(session, realm, linked, "vneid_link_conflict");
      throw new IdentityBrokerException("Danh tính VNeID đã gắn người khác").withMessageCode(MSG_ALREADY_LINKED);
    }
    if (linked == null) {
      FederatedIdentityModel existing = session.users().getFederatedIdentity(realm, person, alias);
      if (existing != null) {
        audit(session, realm, person, "vneid_link_conflict");
        throw new IdentityBrokerException("Người đã gắn danh tính VNeID khác").withMessageCode(MSG_ALREADY_LINKED);
      }
    }
    context.getContextData().put(PERSON_NOTE, person.getId());
  }

  /** Người đúng một bản ghi của tenant, đang có tài khoản và thuộc tenant đó; không thì dừng, không tạo tài khoản. */
  private UserModel matchPerson(KeycloakSession session, RealmModel realm, AuthenticationSessionModel authSession,
      String nationalId) {
    String tenantParam = AuthorizationEndpoint.LOGIN_SESSION_NOTE_ADDITIONAL_REQ_PARAMS_PREFIX
        + cfg(TENANT_PARAM, "tenant_id");
    String tenant = authSession == null ? null : authSession.getClientNote(tenantParam);
    UUID tenantId;
    try {
      tenantId = tenant == null ? null : UUID.fromString(tenant.trim());
    } catch (IllegalArgumentException e) {
      tenantId = null;
    }
    if (tenantId == null || nationalId == null) {
      audit(session, realm, null, "vneid_not_linked");
      throw new IdentityBrokerException("Thiếu tenant hoặc số định danh").withMessageCode(MSG_NOT_LINKED);
    }
    PersonMatcher.Match match;
    try {
      match = persons.match(tenantId, nationalId);
    } catch (IOException | RuntimeException e) {
      LOG.warnf("Không tra được người theo số định danh ở org-service: %s", e.getClass().getSimpleName());
      throw new IdentityBrokerException("org-service không trả lời", e)
          .withMessageCode(Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
    }
    UserModel user = match.personId() == null ? null
        : session.users().getUserById(realm, match.personId().toString());
    if (user == null || !user.isEnabled() || !tenantId.toString().equals(user.getFirstAttribute("tenant_id"))) {
      audit(session, realm, null, match.matches() > 1 ? "vneid_ambiguous_person" : "vneid_not_linked");
      throw new IdentityBrokerException("Không khớp đúng một người").withMessageCode(MSG_NOT_LINKED);
    }
    return user;
  }

  private void audit(KeycloakSession session, RealmModel realm, UserModel user, String error) {
    EventBuilder event = new EventBuilder(realm, session, session.getContext().getConnection())
        .event(EventType.IDENTITY_PROVIDER_LINK_ACCOUNT)
        .detail(Details.IDENTITY_PROVIDER, getConfig().getAlias());
    if (session.getContext().getClient() != null) {
      event.client(session.getContext().getClient());
    }
    if (user != null) {
      event.user(user);
    }
    event.error(error);
  }

  /** Điểm gọi lại có thời hạn và bộ ngắt mạch; lỗi của VNeID hiện MSG-00-04. */
  protected static class VneidEndpoint extends OIDCEndpoint {

    private final VneidIdentityProvider vneid;

    public VneidEndpoint(AuthenticationCallback callback, RealmModel realm, EventBuilder event,
        VneidIdentityProvider provider) {
      super(callback, realm, event, provider);
      this.vneid = provider;
    }

    @GET
    @Override
    public Response authResponse(@QueryParam(OAUTH2_PARAMETER_STATE) String state,
        @QueryParam(OAUTH2_PARAMETER_CODE) String authorizationCode,
        @QueryParam("error") String error,
        @QueryParam("error_description") String errorDescription) {
      if (state == null || authorizationCode == null || error != null) {
        return super.authResponse(state, authorizationCode, error, errorDescription);
      }
      CircuitBreaker breaker = vneid.breaker();
      if (!breaker.allow()) {
        return unavailable();
      }
      TOKEN_ANSWERED.set(false);
      TOKEN_ATTEMPTED.set(false);
      try {
        Response r = super.authResponse(state, authorizationCode, null, errorDescription);
        if (TOKEN_ANSWERED.get()) {
          breaker.success();
          return r;
        }
        if (TOKEN_ATTEMPTED.get() && r.getStatus() == Response.Status.BAD_GATEWAY.getStatusCode()) {
          // Điểm cuối token của VNeID lỗi, quá hạn hoặc không kết nối được.
          breaker.failure();
          return unavailable();
        }
        return r;
      } finally {
        TOKEN_ANSWERED.remove();
        TOKEN_ATTEMPTED.remove();
      }
    }

    private Response unavailable() {
      event.event(EventType.IDENTITY_PROVIDER_LOGIN).error("vneid_unavailable");
      return ErrorPage.error(session, null, Response.Status.BAD_GATEWAY, MSG_UNAVAILABLE);
    }

    @Override
    public SimpleHttpRequest generateTokenRequest(String authorizationCode) {
      TOKEN_ATTEMPTED.set(true);
      SimpleHttpRequest base = super.generateTokenRequest(authorizationCode);
      int timeout = vneid.cfgInt(TIMEOUT_MILLIS, 10_000);
      RequestConfig limits = RequestConfig.custom()
          .setConnectTimeout(timeout)
          .setConnectionRequestTimeout(timeout)
          .setSocketTimeout(timeout)
          .build();
      SimpleHttpRequest bounded = SimpleHttp.create(session).withRequestConfig(limits).doPost(base.getUrl());
      if (base.getHeaders() != null) {
        base.getHeaders().forEach(bounded::header);
      }
      if (base.getParams() != null) {
        base.getParams().forEach(bounded::param);
      }
      return bounded;
    }
  }
}
