package vn.viettel.okrkpi.iam.events;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;

/**
 * DV-02-08 · QT-00 ngoại lệ "mất thiết bị SmartOTP" · {@code issueMfaRecoveryCode}, {@code revokeMfaRecoveryCodes} của
 * {@code platform.yaml}, phục vụ dưới {@code /realms/{realm}/mission-iam/} của Keycloak; cổng APISIX ánh xạ
 * {@code /api/v1/iam/*} vào đây. Kiểm soát: token Bearer của realm, vai trò {@code tenant_admin}, phiên AAL2 còn trong
 * {@value #AAL2_MAX_AGE_SECONDS} giây (BR-00-01, BR-00-02; thiếu thì 401 {@code insufficient_user_authentication}),
 * cùng tenant với người được cấp, không tự cấp cho chính mình (tách vai trò).
 */
public class MissionIamResource {

  static final long AAL2_MAX_AGE_SECONDS = 900;
  static final Set<String> METHODS = Set.of("TRUC_TIEP", "VAN_BAN_DON_VI", "GOI_VIDEO_NOI_BO");
  private static final String PROBLEM = "application/problem+json";

  private final KeycloakSession session;

  public MissionIamResource(KeycloakSession session) {
    this.session = session;
  }

  @POST
  @Path("users/{personId}/mfa-recovery-codes")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response issue(@PathParam("personId") String personId, @HeaderParam("Idempotency-Key") String key,
      @HeaderParam("traceparent") String traceparent, Map<String, Object> body) {
    Caller caller;
    try {
      caller = authorize();
    } catch (Problem p) {
      return p.response();
    }
    if (key == null || key.isBlank() || key.length() > 128) {
      return problem(400, "invalid-request", "Thiếu khoá chống gửi trùng", "Yêu cầu cần tiêu đề Idempotency-Key.");
    }
    Object method = body == null ? null : body.get("verificationMethod");
    Object reason = body == null ? null : body.get("reason");
    Object ttl = body == null ? null : body.getOrDefault("ttlMinutes", 15);
    if (!(method instanceof String m) || !METHODS.contains(m) || !(reason instanceof String r)
        || r.strip().length() < 10 || r.length() > 500 || !(ttl instanceof Integer t) || t < 5 || t > 15) {
      return problem(422, "validation", "Dữ liệu chưa hợp lệ",
          "Cần hình thức xác minh hợp lệ, lý do từ 10 đến 500 ký tự và thời hạn từ 5 đến 15 phút.");
    }
    UserModel target;
    try {
      target = target(caller, personId);
    } catch (Problem p) {
      return p.response();
    }
    String digest = RecoveryCodes.keyDigest(caller.user().getId() + ":" + key);
    if (RecoveryCodeStore.byIdempotency(target, digest).isPresent()) {
      return problem(409, "idempotency-replay", "Mã đã được cấp",
          "Mã khôi phục chỉ hiển thị một lần; muốn cấp lại thì gửi yêu cầu mới.");
    }
    RecoveryCodeStore.Issued issued = RecoveryCodeStore.issue(target, uuid(caller.user().getId()), m, t, digest,
        Instant.now());
    RecoveryEvents.issued(session, target, uuid(caller.user().getId()), m, traceparent);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("codeId", issued.codeId().toString());
    out.put("personId", target.getId());
    out.put("recoveryCode", issued.code());
    out.put("expiresAt", issued.expiresAt().toString());
    out.put("issuedAt", issued.issuedAt().toString());
    return Response.status(201).entity(out).type(MediaType.APPLICATION_JSON).header("Cache-Control", "no-store")
        .build();
  }

  @POST
  @Path("users/{personId}/mfa-recovery-codes/revoke")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response revoke(@PathParam("personId") String personId, @HeaderParam("Idempotency-Key") String key,
      @HeaderParam("traceparent") String traceparent, Map<String, Object> body) {
    try {
      Caller caller = authorize();
      if (key == null || key.isBlank() || key.length() > 128) {
        return problem(400, "invalid-request", "Thiếu khoá chống gửi trùng", "Yêu cầu cần tiêu đề Idempotency-Key.");
      }
      Object reason = body == null ? null : body.get("reason");
      if (!(reason instanceof String r) || r.strip().length() < 5 || r.length() > 500) {
        return problem(422, "validation", "Dữ liệu chưa hợp lệ", "Lý do thu hồi cần từ 5 đến 500 ký tự.");
      }
      UserModel target = target(caller, personId);
      int count = RecoveryCodeStore.revokeAll(target);
      RecoveryEvents.revoked(session, target, uuid(caller.user().getId()), traceparent);
      return Response.ok(Map.of("revokedCount", count)).type(MediaType.APPLICATION_JSON).build();
    } catch (Problem p) {
      return p.response();
    }
  }

  private record Caller(UserModel user, AccessToken token) {
  }

  private Caller authorize() {
    RealmModel realm = session.getContext().getRealm();
    AuthenticationManager.AuthResult auth = new AppAuthManager.BearerTokenAuthenticator(session).setRealm(realm)
        .setConnection(session.getContext().getConnection()).authenticate();
    if (auth == null || auth.getUser() == null) {
      throw new Problem(401, "unauthorized", "Chưa đăng nhập", "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.");
    }
    RoleModel admin = realm.getRole("tenant_admin");
    if (admin == null || !auth.getUser().hasRole(admin)) {
      throw new Problem(403, "forbidden", "Không có quyền", "Không có quyền thực hiện thao tác này.");
    }
    AccessToken t = auth.getToken();
    Long authTime = t.getAuth_time();
    boolean fresh = authTime != null && Instant.now().getEpochSecond() - authTime <= AAL2_MAX_AGE_SECONDS;
    if (!"aal2".equals(t.getAcr()) || !fresh) {
      throw new Problem(401, "insufficient-user-authentication", "Cần xác thực bổ sung",
          "Thao tác cần xác thực hai lớp trong 15 phút gần nhất.");
    }
    return new Caller(auth.getUser(), t);
  }

  private UserModel target(Caller caller, String personId) {
    RealmModel realm = session.getContext().getRealm();
    UserModel target = uuid(personId) == null ? null : session.users().getUserById(realm, personId);
    if (target == null) {
      throw new Problem(404, "not-found", "Không tìm thấy", "Không tìm thấy tài khoản của cán bộ.");
    }
    String mine = caller.user().getFirstAttribute(MissionEventListenerProvider.TENANT_ATTRIBUTE);
    String theirs = target.getFirstAttribute(MissionEventListenerProvider.TENANT_ATTRIBUTE);
    if (mine == null || !mine.equals(theirs)) {
      throw new Problem(403, "forbidden", "Không có quyền", "Cán bộ không thuộc tổ chức do đồng chí quản trị.");
    }
    if (target.getId().equals(caller.user().getId())) {
      throw new Problem(403, "forbidden", "Không có quyền",
          "Không tự cấp hoặc thu hồi mã khôi phục cho chính mình; đề nghị quản trị viên khác thực hiện.");
    }
    return target;
  }

  private static UUID uuid(String v) {
    return RecoveryEvents.uuid(v);
  }

  static Response problem(int status, String type, String title, String detail) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("type", "https://mission.gov.vn/problems/" + type);
    p.put("title", title);
    p.put("status", status);
    p.put("detail", detail);
    Response.ResponseBuilder b = Response.status(status).entity(p).type(PROBLEM);
    if (status == 401 && "insufficient-user-authentication".equals(type)) {
      b.header("WWW-Authenticate", "Bearer error=\"insufficient_user_authentication\", acr_values=\"aal2\", "
          + "max_age=" + AAL2_MAX_AGE_SECONDS);
    }
    return b.build();
  }

  /** Lỗi RFC 9457 dừng xử lý. */
  static final class Problem extends RuntimeException {
    private final int status;
    private final String type;
    private final String title;

    Problem(int status, String type, String title, String detail) {
      super(detail, null, false, false);
      this.status = status;
      this.type = type;
      this.title = title;
    }

    Response response() {
      return problem(status, type, title, getMessage());
    }
  }
}
