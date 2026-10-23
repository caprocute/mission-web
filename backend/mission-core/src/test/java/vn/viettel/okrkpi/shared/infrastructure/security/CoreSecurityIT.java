package vn.viettel.okrkpi.shared.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Resource server JWT của mission-core với chuỗi bộ lọc thật: token ký thử bằng khoá RSA của một
 * JWKS giả lập Keycloak realm {@code mission}. Thiếu, sai chữ ký, sai {@code iss}, hết hạn hoặc
 * {@code unit_id} sai dạng trả 401; token không có {@code tenant_id} trả 403; token đúng tới được
 * thao tác với tenant trong {@link TenantContext} và {@code unit_id} trong chủ thể phân quyền.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {"mission.outbox.relay.enabled=false", "okrkpi.security.auth-enabled=true"})
@AutoConfigureMockMvc
@DirtiesContext
class CoreSecurityIT {

  static final String ISSUER = "http://keycloak.test/realms/mission";
  static final RSAKey KEY = rsa("k1");
  static final RSAKey OTHER = rsa("k1");
  static final HttpServer JWKS = jwks();

  /** Thao tác thử: trả tenant đã đặt và chủ thể phân quyền đọc từ token. */
  @RestController
  static class WhoAmI {
    @GetMapping("/api/v1/test-security/whoami")
    Map<String, Object> whoami() {
      OpaInput.Subject s = new AuthzSubjectResolver().currentSubject();
      return Map.of(
          "tenant", TenantContext.require().value().toString(),
          "user", s.userId(),
          "unit", String.valueOf(s.unitId()),
          "roles", s.roles());
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/certs");
  }

  @AfterAll
  static void stop() {
    JWKS.stop(0);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  private final UUID tenant = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();

  @Test
  void validTokenReachesApiWithTenantUnitAndRoles() throws Exception {
    MvcResult r = call("/api/v1/test-security/whoami", token(KEY, c -> {}));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    JsonNode body = json.readTree(r.getResponse().getContentAsByteArray());
    assertThat(body.path("tenant").asText()).isEqualTo(tenant.toString());
    assertThat(body.path("user").asText()).isEqualTo(user.toString());
    assertThat(body.path("unit").asText()).isEqualTo(unit.toString());
    assertThat(body.path("roles").toString()).contains("AC-02");

    // Thao tác thật của hợp đồng jobs.yaml: qua xác thực, có tenant, mã lạ nên 404 (không 500).
    MvcResult job = call("/api/v1/jobs/" + UUID.randomUUID(), token(KEY, c -> {}));
    assertThat(job.getResponse().getStatus()).isEqualTo(404);
  }

  @Test
  void missingOrInvalidTokenIsUnauthorizedProblem() throws Exception {
    MvcResult none = mvc.perform(get("/api/v1/jobs/" + UUID.randomUUID())).andReturn();
    assertProblem(none, 401);
    assertThat(none.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer");

    assertProblem(call("/api/v1/test-security/whoami", token(OTHER, c -> {})), 401);
    assertProblem(
        call("/api/v1/test-security/whoami", token(KEY, c -> c.issuer("http://khac/realms/x"))),
        401);
    assertProblem(
        call(
            "/api/v1/test-security/whoami",
            token(
                KEY,
                c ->
                    c.issueTime(Date.from(Instant.now().minusSeconds(3600)))
                        .expirationTime(Date.from(Instant.now().minusSeconds(600))))),
        401);
    assertProblem(
        call("/api/v1/test-security/whoami", token(KEY, c -> c.claim("unit_id", "phong-1"))), 401);
    assertProblem(call("/api/v1/test-security/whoami", "khong-phai-jwt"), 401);
  }

  @Test
  void tokenWithoutTenantIsForbiddenProblem() throws Exception {
    MvcResult r = call("/api/v1/test-security/whoami", token(KEY, c -> c.claim("tenant_id", null)));
    assertProblem(r, 403);
    MvcResult bad =
        call("/api/v1/test-security/whoami", token(KEY, c -> c.claim("tenant_id", "khong-uuid")));
    assertProblem(bad, 403);
  }

  private MvcResult call(String path, String bearer) throws Exception {
    return mvc.perform(get(path).header("Authorization", "Bearer " + bearer)).andReturn();
  }

  private void assertProblem(MvcResult r, int status) throws Exception {
    assertThat(r.getResponse().getStatus()).isEqualTo(status);
    assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
    JsonNode body = json.readTree(r.getResponse().getContentAsByteArray());
    assertThat(body.path("status").asInt()).isEqualTo(status);
    assertThat(body.toString()).doesNotContain("Jwt").doesNotContain("signature");
  }

  private String token(RSAKey key, Consumer<JWTClaimsSet.Builder> tweak) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet.Builder c =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(user.toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .claim("tenant_id", tenant.toString())
            .claim("unit_id", unit.toString())
            .claim("realm_access", Map.of("roles", List.of("AC-02")));
    tweak.accept(c);
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID())
                .type(JOSEObjectType.JWT)
                .build(),
            c.build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private static RSAKey rsa(String kid) {
    try {
      return new RSAKeyGenerator(2048).keyID(kid).generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static HttpServer jwks() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      server.createContext(
          "/certs",
          ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
          });
      server.start();
      return server;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
