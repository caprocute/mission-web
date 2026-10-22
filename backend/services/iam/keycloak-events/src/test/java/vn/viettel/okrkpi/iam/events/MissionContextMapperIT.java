package vn.viettel.okrkpi.iam.events;

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
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * DV-02-02: protocol mapper {@code mission-context} trên Keycloak 26 thật. Token sau khi org-service ghi ngữ cảnh
 * mang đúng claim; ngữ cảnh hết hiệu lực bị bỏ; tham số, header của client và việc người dùng tự sửa thuộc tính qua
 * Account API không làm đổi claim.
 */
class MissionContextMapperIT {

  private static final String REALM = "kiem-thu-ngu-canh";
  private static final String TENANT = "0192f000-0000-7000-8000-0000000000a1";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
  private static final String USER_PASSWORD = "Kt-" + UUID.randomUUID();
  private static GenericContainer<?> keycloak;
  private static String base;

  @BeforeAll
  static void start() throws Exception {
    keycloak = new GenericContainer<>(System.getProperty("keycloak.image", "mirror.gcr.io/keycloak/keycloak:26.4.0"))
        .withExposedPorts(8080)
        .withCopyFileToContainer(MountableFile.forHostPath(Path.of(System.getProperty("provider.jar",
            "target/mission-keycloak-events.jar"))), "/opt/keycloak/providers/mission-keycloak-events.jar")
        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "kc-admin").withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
        .withEnv("MISSION_EVENTS_QUEUE_DIR", "/tmp/mission-events").withCommand("start-dev")
        .waitingFor(Wait.forHttp("/realms/master").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));
    keycloak.start();
    base = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    ObjectNode realm = JSON.createObjectNode().put("realm", REALM).put("enabled", true);
    realm.putObject("attributes").put("mission.cell", "cell-01");
    assertThat(admin("POST", "", realm).statusCode()).isEqualTo(201);
    ObjectNode profile = (ObjectNode) JSON.readTree(admin("GET", "/users/profile", null).body());
    profile.put("unmanagedAttributePolicy", "ADMIN_EDIT");
    assertThat(admin("PUT", "/users/profile", profile).statusCode()).isEqualTo(200);
    ObjectNode client = JSON.createObjectNode().put("clientId", "kiem-thu-cli").put("publicClient", true)
        .put("directAccessGrantsEnabled", true).put("standardFlowEnabled", false);
    ObjectNode mapper = client.putArray("protocolMappers").addObject();
    mapper.put("name", "mission-context").put("protocol", "openid-connect")
        .put("protocolMapper", MissionContextMapper.PROVIDER_ID);
    mapper.putObject("config").put("access.token.claim", "true").put("id.token.claim", "true")
        .put("userinfo.token.claim", "true");
    HttpResponse<String> created = admin("POST", "/clients", client);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
  }

  @AfterAll
  static void stop() {
    if (keycloak != null) {
      keycloak.stop();
    }
  }

  @Test
  void tokenMangNguCanhDaGhiVaKhongDoiTheoThamSoClient() throws Exception {
    String id = createUser("can-bo-ngu-canh");
    String unit = UUID.randomUUID().toString();
    String assignment = UUID.randomUUID().toString();
    writeContext(id, unit, assignment, LocalDate.now().plusDays(10).toString());

    JsonNode claims = claims(login("can-bo-ngu-canh", "&claims=" + URLEncoder.encode(
        "{\"access_token\":{\"unit_id\":{\"value\":\"gia-mao\"}}}", StandardCharsets.UTF_8) + "&unit_id=gia-mao"));
    assertThat(claims.path("tenant_id").asText()).isEqualTo(TENANT);
    assertThat(claims.path("tenant").asText()).isEqualTo(TENANT);
    assertThat(claims.path("cell").asText()).isEqualTo("cell-01");
    assertThat(claims.path("unit_id").asText()).isEqualTo(unit);
    assertThat(claims.path("active_assignment_id").asText()).isEqualTo(assignment);
    assertThat(claims.path("flows").toString()).isEqualTo("[\"DANG\"]");
    assertThat(claims.path("roles").toString()).contains("member", "leader");
    assertThat(claims.path("org").path("unit").asText()).isEqualTo(unit);
    assertThat(claims.path("org").path("assignment").asText()).isEqualTo(assignment);

    // Người dùng tự sửa thuộc tính qua Account API không được nhận.
    String access = loginToken("can-bo-ngu-canh");
    JsonNode account = JSON.readTree(send("GET", base + "/realms/" + REALM + "/account", access, null).body());
    ObjectNode forged = (ObjectNode) account;
    forged.putObject("attributes").putArray("mission_context_unit").add("gia-mao");
    send("POST", base + "/realms/" + REALM + "/account", access, JSON.writeValueAsString(forged));
    assertThat(claims(login("can-bo-ngu-canh", "")).path("unit_id").asText()).isEqualTo(unit);
  }

  @Test
  void nguCanhHetHieuLucBiBo() throws Exception {
    String id = createUser("can-bo-het-han");
    writeContext(id, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        LocalDate.now().minusDays(2).toString());
    JsonNode claims = claims(login("can-bo-het-han", ""));
    assertThat(claims.path("tenant_id").asText()).isEqualTo(TENANT);
    assertThat(claims.has("unit_id")).isFalse();
    assertThat(claims.has("org")).isFalse();
  }

  private static void writeContext(String id, String unit, String assignment, String validTo) throws Exception {
    ObjectNode user = (ObjectNode) JSON.readTree(admin("GET", "/users/" + id, null).body());
    ObjectNode attrs = (ObjectNode) user.get("attributes");
    attrs.putArray("mission_context_unit").add(unit);
    attrs.putArray("mission_context_assignment").add(assignment);
    attrs.putArray("mission_context_roles").add("leader").add("member");
    attrs.putArray("mission_context_flows").add("DANG");
    attrs.putArray("mission_context_valid_to").add(validTo);
    assertThat(admin("PUT", "/users/" + id, user).statusCode()).isEqualTo(204);
  }

  private static String createUser(String username) throws Exception {
    ObjectNode user = JSON.createObjectNode().put("username", username).put("enabled", true)
        .put("emailVerified", true).put("firstName", "Cán bộ").put("lastName", "Kiểm thử")
        .put("email", username + "@kiem-thu.invalid");
    user.putObject("attributes").putArray("tenant_id").add(TENANT);
    ArrayNode creds = user.putArray("credentials");
    creds.addObject().put("type", "password").put("value", USER_PASSWORD).put("temporary", false);
    assertThat(admin("POST", "/users", user).statusCode()).isEqualTo(201);
    return JSON.readTree(admin("GET", "/users?exact=true&username=" + username, null).body()).get(0).get("id")
        .asText();
  }

  private static JsonNode login(String username, String extra) throws Exception {
    HttpResponse<String> r = token("grant_type=password&client_id=kiem-thu-cli&username=" + username + "&password="
        + URLEncoder.encode(USER_PASSWORD, StandardCharsets.UTF_8) + extra, REALM, "gia-mao");
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return JSON.readTree(r.body());
  }

  private static String loginToken(String username) throws Exception {
    return login(username, "").get("access_token").asText();
  }

  private static JsonNode claims(JsonNode tokenResponse) throws Exception {
    String[] parts = tokenResponse.get("access_token").asText().split("\\.");
    return JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
  }

  private static HttpResponse<String> token(String form, String realm, String forgedHeader) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/realms/" + realm
        + "/protocol/openid-connect/token")).header("Content-Type", "application/x-www-form-urlencoded");
    if (forgedHeader != null) {
      b.header("X-Tenant-Id", forgedHeader).header("X-Unit-Id", forgedHeader);
    }
    return HTTP.send(b.POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> send(String method, String url, String bearer, String body) throws Exception {
    return HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + bearer)
        .header("Content-Type", "application/json").header("Accept", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        .build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> admin(String method, String path, Object body) throws Exception {
    String token = JSON.readTree(token("grant_type=password&client_id=admin-cli&username=kc-admin&password="
        + URLEncoder.encode(ADMIN_PASSWORD, StandardCharsets.UTF_8), "master", null).body()).get("access_token")
        .asText();
    String url = path.isEmpty() ? base + "/admin/realms" : base + "/admin/realms/" + REALM + path;
    return send(method, url, token, body == null ? null : JSON.writeValueAsString(body));
  }
}
