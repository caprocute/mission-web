package vn.viettel.okrkpi.shared.infrastructure.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * DV-02-05: {@link KeycloakAdminClient} trên Keycloak 26 thật. Sau khi thu hồi, refresh token cũ bị
 * từ chối; tài khoản bị buộc đổi mật khẩu; quản trị tenant lọc đúng theo {@code tenant_id}.
 */
class KeycloakAdminClientIT {

  private static final String REALM = "mission";
  private static final String TENANT_A = "0192f000-0000-7000-8000-0000000000a1";
  private static final String TENANT_B = "0192f000-0000-7000-8000-0000000000b2";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
  private static final String USER_PASSWORD = "Kt-" + UUID.randomUUID();
  private static final String CLIENT_SECRET = UUID.randomUUID().toString();

  private static GenericContainer<?> keycloak;
  private static String base;

  @BeforeAll
  static void start() throws Exception {
    keycloak =
        new GenericContainer<>(
                System.getProperty("keycloak.image", "mirror.gcr.io/keycloak/keycloak:26.4.0"))
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "kc-admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withCommand("start-dev")
            .waitingFor(
                Wait.forHttp("/realms/master")
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(4)));
    keycloak.start();
    base = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    seedRealm();
  }

  @AfterAll
  static void stop() {
    if (keycloak != null) {
      keycloak.stop();
    }
  }

  @Test
  void thuHoiPhienLamRefreshTokenCuBiTuChoiVaBuocDoiMatKhau() throws Exception {
    String reporter = createUser("can-bo-bao", TENANT_A, false);
    String refresh1 = login("can-bo-bao").get("refresh_token").asText();
    login("can-bo-bao");
    KeycloakAdminClient admin = adminClient();

    assertThat(admin.revokeAllSessions(UUID.fromString(reporter))).isEqualTo(2);
    HttpResponse<String> refreshed = refresh(refresh1);
    assertThat(refreshed.statusCode()).isEqualTo(400);
    assertThat(refreshed.body()).contains("invalid_grant");

    admin.requirePasswordChange(UUID.fromString(reporter));
    JsonNode user = JSON.readTree(adminApi("GET", "/users/" + reporter, null).body());
    assertThat(user.get("requiredActions").toString()).contains("UPDATE_PASSWORD");
    assertThat(user.get("attributes").get("tenant_id").get(0).asText()).isEqualTo(TENANT_A);
    assertThat(admin.revokeAllSessions(UUID.fromString(reporter))).isZero();
  }

  @Test
  void quanTriTenantLocTheoTenant() throws Exception {
    String adminA = createUser("quan-tri-a", TENANT_A, true);
    createUser("quan-tri-b", TENANT_B, true);
    createUser("can-bo-a", TENANT_A, false);

    assertThat(adminClient().tenantAdmins(TenantId.parse(TENANT_A)))
        .containsExactly(UUID.fromString(adminA));
  }

  private static KeycloakAdminClient adminClient() {
    return new KeycloakAdminClient(
        RestClient.builder(),
        new KeycloakAdminProperties(base, REALM, "mission-core", CLIENT_SECRET, "tenant_admin"),
        Clock.systemUTC());
  }

  private static void seedRealm() throws Exception {
    ObjectNode realm = JSON.createObjectNode().put("realm", REALM).put("enabled", true);
    assertThat(adminApi("POST", "", realm).statusCode()).isEqualTo(201);
    ObjectNode profile = (ObjectNode) JSON.readTree(adminApi("GET", "/users/profile", null).body());
    profile.put("unmanagedAttributePolicy", "ADMIN_EDIT");
    assertThat(adminApi("PUT", "/users/profile", profile).statusCode()).isEqualTo(200);
    ObjectNode cli =
        JSON.createObjectNode()
            .put("clientId", "kiem-thu-cli")
            .put("publicClient", true)
            .put("directAccessGrantsEnabled", true)
            .put("standardFlowEnabled", false);
    assertThat(adminApi("POST", "/clients", cli).statusCode()).isEqualTo(201);
    ObjectNode svc =
        JSON.createObjectNode()
            .put("clientId", "mission-core")
            .put("publicClient", false)
            .put("secret", CLIENT_SECRET)
            .put("serviceAccountsEnabled", true)
            .put("standardFlowEnabled", false);
    assertThat(adminApi("POST", "/clients", svc).statusCode()).isEqualTo(201);
    String svcId = clientId("mission-core");
    String saUser =
        JSON.readTree(adminApi("GET", "/clients/" + svcId + "/service-account-user", null).body())
            .get("id")
            .asText();
    String rm = clientId("realm-management");
    ArrayNode roles = JSON.createArrayNode();
    for (String r : new String[] {"view-users", "manage-users", "query-users", "view-realm"}) {
      roles.add(JSON.readTree(adminApi("GET", "/clients/" + rm + "/roles/" + r, null).body()));
    }
    assertThat(
            adminApi("POST", "/users/" + saUser + "/role-mappings/clients/" + rm, roles)
                .statusCode())
        .isEqualTo(204);
    assertThat(
            adminApi("POST", "/roles", JSON.createObjectNode().put("name", "tenant_admin"))
                .statusCode())
        .isEqualTo(201);
  }

  private static String createUser(String username, String tenant, boolean tenantAdmin)
      throws Exception {
    ObjectNode user =
        JSON.createObjectNode()
            .put("username", username)
            .put("enabled", true)
            .put("emailVerified", true)
            .put("firstName", "Cán bộ")
            .put("lastName", "Kiểm thử")
            .put("email", username + "@kiem-thu.invalid");
    user.putObject("attributes").putArray("tenant_id").add(tenant);
    user.putArray("credentials")
        .addObject()
        .put("type", "password")
        .put("value", USER_PASSWORD)
        .put("temporary", false);
    assertThat(adminApi("POST", "/users", user).statusCode()).isEqualTo(201);
    String id =
        JSON.readTree(adminApi("GET", "/users?exact=true&username=" + username, null).body())
            .get(0)
            .get("id")
            .asText();
    if (tenantAdmin) {
      ArrayNode role = JSON.createArrayNode();
      role.add(JSON.readTree(adminApi("GET", "/roles/tenant_admin", null).body()));
      assertThat(adminApi("POST", "/users/" + id + "/role-mappings/realm", role).statusCode())
          .isEqualTo(204);
    }
    return id;
  }

  private static String clientId(String clientId) throws Exception {
    return JSON.readTree(adminApi("GET", "/clients?clientId=" + clientId, null).body())
        .get(0)
        .get("id")
        .asText();
  }

  private static JsonNode login(String username) throws Exception {
    HttpResponse<String> r =
        token(
            "grant_type=password&client_id=kiem-thu-cli&username="
                + username
                + "&password="
                + URLEncoder.encode(USER_PASSWORD, StandardCharsets.UTF_8),
            REALM);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return JSON.readTree(r.body());
  }

  private static HttpResponse<String> refresh(String refreshToken) throws Exception {
    return token(
        "grant_type=refresh_token&client_id=kiem-thu-cli&refresh_token=" + refreshToken, REALM);
  }

  private static HttpResponse<String> token(String form, String realm) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(
                URI.create(base + "/realms/" + realm + "/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> adminApi(String method, String path, Object body)
      throws Exception {
    String admin =
        JSON.readTree(
                token(
                        "grant_type=password&client_id=admin-cli&username=kc-admin&password="
                            + URLEncoder.encode(ADMIN_PASSWORD, StandardCharsets.UTF_8),
                        "master")
                    .body())
            .get("access_token")
            .asText();
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(base + "/admin/realms/" + REALM + path).normalize())
            .header("Authorization", "Bearer " + admin)
            .header("Content-Type", "application/json");
    if (path.isEmpty()) {
      b.uri(URI.create(base + "/admin/realms"));
    }
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }
}
