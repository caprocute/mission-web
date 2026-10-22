package vn.viettel.okrkpi.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import vn.viettel.okrkpi.shared.error.ErrorCatalog;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.error.W3cTraceIdResolver;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.observability.LoggingContext;

/** NEN-010 mục 2: tenant chỉ lấy từ JWT đã xác thực; thiếu claim thì 403 Problem Details. */
class TenantContextFilterTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final TenantContextFilter filter =
      new TenantContextFilter(
          new TenantProperties(null, null),
          new ProblemFactory(new W3cTraceIdResolver(), ErrorCatalog.standard()),
          mapper);

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    MDC.clear();
  }

  private static void authenticate(String claim, Object value) {
    Jwt.Builder jwt =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("u1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60));
    if (value != null) {
      jwt.claim(claim, value);
    }
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build()));
  }

  @Test
  void datTenantVaMdcTrongYeuCauRoiDon() throws Exception {
    UUID id = UUID.randomUUID();
    authenticate("tenant_id", id.toString());
    AtomicReference<TenantId> seen = new AtomicReference<>();
    AtomicReference<String> mdc = new AtomicReference<>();
    MockFilterChain chain =
        new MockFilterChain(
            new jakarta.servlet.http.HttpServlet() {
              @Override
              protected void service(
                  jakarta.servlet.http.HttpServletRequest req,
                  jakarta.servlet.http.HttpServletResponse res) {
                seen.set(TenantContext.require());
                mdc.set(MDC.get(LoggingContext.TENANT_ID));
              }
            });
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/task/tasks");
    request.addHeader("X-Tenant-Id", UUID.randomUUID().toString());
    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(seen.get()).isEqualTo(TenantId.of(id));
    assertThat(mdc.get()).isEqualTo(id.toString());
    assertThat(TenantContext.current()).isEmpty();
    assertThat(MDC.get(LoggingContext.TENANT_ID)).isNull();
  }

  @Test
  void thieuClaimTra403Problem() throws Exception {
    authenticate("tenant_id", null);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/task/tasks"), response, chain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(mapper.readTree(response.getContentAsByteArray()).path("status").asInt())
        .isEqualTo(403);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void claimSaiDinhDangTra403() throws Exception {
    authenticate("tenant_id", "khong-phai-uuid");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(new MockHttpServletRequest("GET", "/x"), response, chain);
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void yeuCauKhongDungJwtDiQuaKhongCoTenant() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("u", "p"));
    AtomicReference<Boolean> hadTenant = new AtomicReference<>();
    MockFilterChain chain =
        new MockFilterChain(
            new jakarta.servlet.http.HttpServlet() {
              @Override
              protected void service(
                  jakarta.servlet.http.HttpServletRequest req,
                  jakarta.servlet.http.HttpServletResponse res) {
                hadTenant.set(TenantContext.current().isPresent());
              }
            });
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
    request.addHeader("X-Tenant-Id", UUID.randomUUID().toString());
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    assertThat(hadTenant.get()).isFalse();
  }

  private final TenantContextFilter delegating =
      new TenantContextFilter(
          new TenantProperties(null, null, java.util.List.of("mission-core"), null),
          new ProblemFactory(new W3cTraceIdResolver(), ErrorCatalog.standard()),
          mapper);

  private static void authenticateService(String client, String username) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("t")
                    .header("alg", "RS256")
                    .subject(UUID.randomUUID().toString())
                    .claim("azp", client)
                    .claim("preferred_username", username)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60))
                    .build()));
  }

  private TenantId seenThrough(TenantContextFilter f, String header, MockHttpServletResponse res)
      throws Exception {
    AtomicReference<TenantId> seen = new AtomicReference<>();
    MockFilterChain chain =
        new MockFilterChain(
            new jakarta.servlet.http.HttpServlet() {
              @Override
              protected void service(
                  jakarta.servlet.http.HttpServletRequest req,
                  jakarta.servlet.http.HttpServletResponse r) {
                seen.set(TenantContext.require());
              }
            });
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/workflow/sagas");
    if (header != null) {
      request.addHeader("X-Tenant-Id", header);
    }
    f.doFilter(request, res, chain);
    return seen.get();
  }

  @Test
  void clientDichVuDuocKhaiLayTenantTuHeader() throws Exception {
    UUID id = UUID.randomUUID();
    authenticateService("mission-core", "service-account-mission-core");
    assertThat(seenThrough(delegating, id.toString(), new MockHttpServletResponse()))
        .isEqualTo(TenantId.of(id));
    assertThat(TenantContext.current()).isEmpty();
  }

  @Test
  void headerBiBoQuaKhiClientChuaKhaiHoacLaTokenNguoiDung() throws Exception {
    String header = UUID.randomUUID().toString();
    // Mặc định không client nào được phép.
    authenticateService("mission-core", "service-account-mission-core");
    MockHttpServletResponse res = new MockHttpServletResponse();
    assertThat(seenThrough(filter, header, res)).isNull();
    assertThat(res.getStatus()).isEqualTo(403);
    // Client khác danh sách.
    authenticateService("integration-gateway", "service-account-integration-gateway");
    res = new MockHttpServletResponse();
    assertThat(seenThrough(delegating, header, res)).isNull();
    assertThat(res.getStatus()).isEqualTo(403);
    // Người dùng đăng nhập qua client mission-core không phải tài khoản dịch vụ.
    authenticateService("mission-core", "nguyen.van.a");
    res = new MockHttpServletResponse();
    assertThat(seenThrough(delegating, header, res)).isNull();
    assertThat(res.getStatus()).isEqualTo(403);
    // Thiếu header.
    authenticateService("mission-core", "service-account-mission-core");
    res = new MockHttpServletResponse();
    assertThat(seenThrough(delegating, null, res)).isNull();
    assertThat(res.getStatus()).isEqualTo(403);
  }

  @Test
  void claimTenantThangHeaderCuaClientDichVu() throws Exception {
    UUID claimed = UUID.randomUUID();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("t")
                    .header("alg", "RS256")
                    .subject("s")
                    .claim("azp", "mission-core")
                    .claim("preferred_username", "service-account-mission-core")
                    .claim("tenant_id", claimed.toString())
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60))
                    .build()));
    assertThat(seenThrough(delegating, UUID.randomUUID().toString(), new MockHttpServletResponse()))
        .isEqualTo(TenantId.of(claimed));
  }
}
