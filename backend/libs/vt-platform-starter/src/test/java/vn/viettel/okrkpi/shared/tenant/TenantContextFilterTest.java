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
}
