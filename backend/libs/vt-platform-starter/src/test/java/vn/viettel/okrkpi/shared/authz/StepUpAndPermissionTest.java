package vn.viettel.okrkpi.shared.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ErrorCatalog;

class StepUpAndPermissionTest {

  private static final Instant FIXED_NOW = Instant.ofEpochSecond(10_000L);
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
  private static final UUID TENANT_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID TENANT_15M = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void stepUpValidatorEnforcesDefault5MinAndTenantOverride15Min() {
    OpaProperties props = new OpaProperties();
    props.setStepUpWindow(Duration.ofMinutes(5));
    props.setTenantStepUpWindows(Map.of(TENANT_15M.toString(), Duration.ofMinutes(15)));
    StepUpValidator validator = new StepUpValidator(props, FIXED_CLOCK);

    OpaInput.Subject freshAal2 =
        OpaInput.Subject.user(
            "u1",
            TENANT_1.toString(),
            "unit-1",
            List.of("leader"),
            "urn:viettel:aal2",
            FIXED_NOW.getEpochSecond() - 250,
            List.of("DANG"));
    validator.verifyStepUp(freshAal2, "approvePlan");

    OpaInput.Subject expiredForDefault =
        OpaInput.Subject.user(
            "u1",
            TENANT_1.toString(),
            "unit-1",
            List.of("leader"),
            "aal2",
            FIXED_NOW.getEpochSecond() - 400,
            List.of("DANG"));
    assertThatThrownBy(() -> validator.verifyStepUp(expiredForDefault, "approvePlan"))
        .isInstanceOf(StepUpRequiredException.class)
        .satisfies(
            ex -> {
              StepUpRequiredException s = (StepUpRequiredException) ex;
              assertThat(s.getMaxAgeSeconds()).isEqualTo(300L);
              assertThat(s.wwwAuthenticateHeader())
                  .isEqualTo(
                      "Bearer error=\"insufficient_user_authentication\", acr_values=\"aal2\", max_age=300");
            });

    // Cùng tuổi 400s nhưng ở tenant cấu hình 15 phút (900s theo BR-00-02) thì được phép.
    OpaInput.Subject allowedFor15MinTenant =
        OpaInput.Subject.user(
            "u1",
            TENANT_15M.toString(),
            "unit-1",
            List.of("leader"),
            "aal2",
            FIXED_NOW.getEpochSecond() - 400,
            List.of("DANG"));
    validator.verifyStepUp(allowedFor15MinTenant, "approvePlan");

    // Thiếu acr=aal2 thì bị từ chối với 401 step-up.
    OpaInput.Subject aal1Subject =
        OpaInput.Subject.user(
            "u1",
            TENANT_1.toString(),
            "unit-1",
            List.of("leader"),
            "aal1",
            FIXED_NOW.getEpochSecond(),
            List.of("DANG"));
    assertThatThrownBy(() -> validator.verifyStepUp(aal1Subject, "approvePlan"))
        .isInstanceOf(StepUpRequiredException.class);
  }

  @Test
  void authzProblemHandlerBuildsRfc9470Response() {
    AuthzProblemHandler handler = new AuthzProblemHandler();
    StepUpRequiredException ex =
        new StepUpRequiredException("approvePlan", 300L, "Cần xác thực bước lên AAL2");
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/plans/123/approve");

    ResponseEntity<ProblemDetail> resp = handler.handleStepUpRequired(ex, req);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(resp.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .isEqualTo(
            "Bearer error=\"insufficient_user_authentication\", acr_values=\"aal2\", max_age=300");
    ProblemDetail body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.getStatus()).isEqualTo(401);
    assertThat(body.getProperties())
        .containsEntry("code", "COMMON-401-STEP-UP-REQUIRED")
        .containsEntry("msg", "MSG-00-02")
        .containsEntry("acr_values", "aal2")
        .containsEntry("max_age", 300L);
  }

  @Test
  void subjectResolverExtractsJwtAndDetailsClaims() {
    Jwt jwt =
        Jwt.withTokenValue("tok")
            .header("alg", "RS256")
            .subject("user-99")
            .claim("tenant_id", TENANT_1.toString())
            .claim("unit_id", "unit-10")
            .claim("acr", "urn:viettel:aal2")
            .claim("auth_time", 9900L)
            .claim("flows", List.of("DANG"))
            .claim("realm_access", Map.of("roles", List.of("leader", "manager")))
            .build();
    JwtAuthenticationToken token =
        new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_member")));
    SecurityContextHolder.getContext().setAuthentication(token);

    AuthzSubjectResolver resolver = new AuthzSubjectResolver();
    OpaInput.Subject subject = resolver.currentSubject();

    assertThat(subject.kind()).isEqualTo("user");
    assertThat(subject.userId()).isEqualTo("user-99");
    assertThat(subject.tenantId()).isEqualTo(TENANT_1.toString());
    assertThat(subject.unitId()).isEqualTo("unit-10");
    assertThat(subject.acr()).isEqualTo("aal2");
    assertThat(subject.authTime()).isEqualTo(9900L);
    assertThat(subject.roles()).containsExactly("leader", "manager", "member");
    assertThat(subject.flows()).containsExactly("DANG");

    // Kiểm tra UsernamePasswordAuthenticationToken với details map
    UsernamePasswordAuthenticationToken customAuth =
        new UsernamePasswordAuthenticationToken(
            "user-77", "N/A", List.of(new SimpleGrantedAuthority("ROLE_appraiser")));
    customAuth.setDetails(
        Map.of(
            "tenant_id",
            TENANT_1.toString(),
            "unit_id",
            "unit-20",
            "acr",
            "aal2",
            "auth_time",
            "9950",
            "flows",
            List.of("NHA_NUOC")));
    SecurityContextHolder.getContext().setAuthentication(customAuth);

    OpaInput.Subject fromDetails = resolver.currentSubject();
    assertThat(fromDetails.userId()).isEqualTo("user-77");
    assertThat(fromDetails.roles()).containsExactly("appraiser");
    assertThat(fromDetails.acr()).isEqualTo("aal2");
    assertThat(fromDetails.authTime()).isEqualTo(9950L);
  }

  @Test
  void subjectResolverTreatsKeycloakServiceAccountWithRealmRolesAsClient() {
    Jwt jwt =
        Jwt.withTokenValue("tok")
            .header("alg", "RS256")
            .subject("svc-1")
            .claim("azp", "analytics-api")
            .claim("preferred_username", "service-account-analytics-api")
            .claim(
                "realm_access",
                Map.of(
                    "roles", List.of("service_client", "offline_access", "default-roles-mission")))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

    OpaInput.Subject subject = new AuthzSubjectResolver().currentSubject();

    assertThat(subject.kind()).isEqualTo("client");
    assertThat(subject.clientId()).isEqualTo("analytics-api");
  }

  @Test
  void permissionInterceptorAllowsAndMapsDenyReasonsToContractErrorCodes() {
    List<AuditEvent> audits = new ArrayList<>();
    AuditLogger auditLogger = audits::add;
    StubOpaClient stubOpa = new StubOpaClient();
    OpaProperties props = new OpaProperties();
    StepUpValidator stepUpValidator = new StepUpValidator(props, FIXED_CLOCK);
    AuthzSubjectResolver resolver = new AuthzSubjectResolver();

    Jwt jwt =
        Jwt.withTokenValue("tok")
            .header("alg", "RS256")
            .subject("nv1")
            .claim("tenant_id", TENANT_1.toString())
            .claim("unit_id", "u1")
            .claim("acr", "aal2")
            .claim("auth_time", FIXED_NOW.getEpochSecond() - 60)
            .claim("realm_access", Map.of("roles", List.of("manager", "leader")))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

    PermissionInterceptor interceptor =
        new PermissionInterceptor(
            stubOpa,
            OperationPolicyCatalog.getDefault(),
            stepUpValidator,
            resolver,
            ErrorCatalog.standard(),
            auditLogger,
            FIXED_CLOCK);

    ProxyFactory pf = new ProxyFactory(new SampleAppService());
    pf.addAdvice(interceptor);
    SampleAppService proxy = (SampleAppService) pf.getProxy();

    // 1. Cho phép + gắn cờ nghĩa vụ c2_access_log
    stubOpa.nextDecision = new OpaDecision(true, Set.of(), Set.of("c2_access_log"), "rev-1", false);
    AuthzResource res =
        AuthzResource.builder()
            .type("task")
            .id("t-100")
            .ownerUnitId("u1")
            .classification("C2")
            .build();
    String out = proxy.approveTask(res);
    assertThat(out).isEqualTo("ok:true");
    assertThat(audits).hasSize(1);
    assertThat(audits.getFirst().action()).isEqualTo("assignTask:OBLIGATION");
    // Mã thao tác đi kèm lớp API: OPA cho client (AC-11) qua theo thao tác hợp đồng khai
    // service_client.
    assertThat(stubOpa.lastInput.request().operation()).isEqualTo("assignTask");

    // 2. Ngoài phạm vi -> COMMON-403-OUT-OF-SCOPE
    stubOpa.nextDecision = new OpaDecision(false, Set.of("out_of_scope"), Set.of(), "rev-1", false);
    assertThatThrownBy(() -> proxy.approveTask(res))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errorCode().code())
                    .isEqualTo("COMMON-403-OUT-OF-SCOPE"));

    // 3. Vi phạm tách vai trò trên thẩm định -> APPRAISAL-403-SEPARATION-OF-DUTIES
    stubOpa.nextDecision =
        new OpaDecision(false, Set.of("separation_of_duties"), Set.of(), "rev-1", false);
    assertThatThrownBy(() -> proxy.approveTask(res))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errorCode().code())
                    .isEqualTo("TASK-403-SAME-AUTHOR"));

    // 4. Vi phạm tự duyệt mục tiêu OKR -> OKR-403-SELF-APPROVAL
    assertThatThrownBy(() -> proxy.approveOkr(res))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errorCode().code())
                    .isEqualTo("OKR-403-SELF-APPROVAL"));

    // 5. Vi phạm nguyên tắc bốn mắt -> OVERSIGHT-403-FOUR-EYES
    stubOpa.nextDecision =
        new OpaDecision(false, Set.of("four_eyes_required"), Set.of(), "rev-1", false);
    assertThatThrownBy(() -> proxy.approveReopenPeriod(res))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errorCode().code())
                    .isEqualTo("OVERSIGHT-403-FOUR-EYES"));
  }

  static class SampleAppService {

    @RequiresPermission(action = "SCR-0501.giao", operationId = "assignTask")
    public String approveTask(AuthzResource resource) {
      return "ok:" + AuthzDecisionContext.hasFlag("c2_access_log");
    }

    @RequiresPermission(action = "SCR-0203.duyet", operationId = "approveOkrObjective")
    public String approveOkr(AuthzResource resource) {
      return "okr-ok";
    }

    @RequiresPermission(action = "SCR-1301.duyet-2", operationId = "approveReopenRequest")
    public String approveReopenPeriod(AuthzResource resource) {
      return "reopen-ok";
    }
  }

  static class StubOpaClient implements OpaClient {
    OpaDecision nextDecision = new OpaDecision(true, Set.of(), Set.of(), "rev-1", false);
    OpaInput lastInput;

    @Override
    public OpaDecision decide(OpaInput input) {
      lastInput = input;
      return nextDecision;
    }

    @Override
    public JsonNode compile(String query, Map<String, Object> input, List<String> unknowns) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String currentBundleRevision() {
      return "rev-1";
    }

    @Override
    public void invalidateCache(String newBundleRevision) {}
  }
}
