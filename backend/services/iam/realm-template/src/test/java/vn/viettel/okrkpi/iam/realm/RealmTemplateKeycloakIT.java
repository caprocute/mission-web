package vn.viettel.okrkpi.iam.realm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Kiểm thử tích hợp mẫu realm production (DV-02-01) trên Keycloak 26 trắng.
 * Mẫu được nạp bằng chính keycloak-config-cli (container) với biến lấy từ values.yaml của chart iam.
 *
 * Truy vết: QT-00 → SCR-0001 → OIDC /realms/<realm>/protocol/openid-connect/* → SVC-02; BR-00-05, MSG-00-01,
 * MSG-00-02; ADR-08; TL-04 SEC-14..SEC-17.
 *
 * Hai realm từ cùng một mẫu: {@code okrkpi} với giá trị production (chỉ đổi sslRequired sang external vì
 * kiểm thử chạy HTTP), và {@code okrkpi-khoa-nhanh} rút thời gian khoá tạm còn 3 giây để kiểm khoá cố định
 * ở lần sai thứ 20 mà không phải chờ 15 phút mỗi lần.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealmTemplateKeycloakIT {

  private static final Logger LOG = LoggerFactory.getLogger(RealmTemplateKeycloakIT.class);

  static final String REALM = "okrkpi";
  static final String FAST_REALM = "okrkpi-khoa-nhanh";
  static final int FAST_WAIT_SECONDS = 3;

  /** Thông tin quản trị và bí mật chỉ sinh ngẫu nhiên lúc chạy kiểm thử. */
  private final String adminUser = "kc-admin-kiem-thu";
  private final String adminPassword = UUID.randomUUID().toString();
  private final String apisixSecret = UUID.randomUUID().toString();

  private Network network;
  private GenericContainer<?> keycloak;
  private String baseUrl;
  private KeycloakAdmin admin;
  private final JsonNode values = RepoFiles.chartValues();

  @BeforeAll
  void startKeycloakAndImport() {
    network = Network.newNetwork();
    keycloak = new GenericContainer<>(System.getProperty("keycloak.image", "keycloak/keycloak:26.4.0"))
        .withNetwork(network)
        .withNetworkAliases("keycloak")
        .withExposedPorts(8080)
        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", adminUser)
        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", adminPassword)
        // Mẫu realm dùng mapper mission-context-mapper của DV-02-02 (module keycloak-events dựng trước).
        .withCopyFileToContainer(MountableFile.forHostPath(java.nio.file.Path.of(System.getProperty("provider.jar",
            "../keycloak-events/target/mission-keycloak-events.jar"))), "/opt/keycloak/providers/mission-keycloak-events.jar")
        .withEnv("MISSION_EVENTS_QUEUE_DIR", "/tmp/mission-events")
        .withCommand("start-dev")
        .waitingFor(Wait.forHttp("/realms/master").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));
    keycloak.start();
    baseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    admin = new KeycloakAdmin(baseUrl, adminUser, adminPassword);

    importRealm(RepoFiles.envFromChartValues(REALM, apisixSecret, Map.of("MISSION_SSL_REQUIRED", "external")));
    importRealm(RepoFiles.envFromChartValues(FAST_REALM, apisixSecret, Map.of(
        "MISSION_SSL_REQUIRED", "external",
        "MISSION_BF_WAIT", String.valueOf(FAST_WAIT_SECONDS))));
  }

  /** Chạy keycloak-config-cli như Job Helm: cùng tệp mẫu, cùng cơ chế thay biến $(env:...). */
  private void importRealm(Map<String, String> env) {
    try (GenericContainer<?> cli = new GenericContainer<>(
        System.getProperty("keycloak-config-cli.image", "adorsys/keycloak-config-cli:6.5.1-26"))) {
      cli.withNetwork(network)
          .withEnv("KEYCLOAK_URL", "http://keycloak:8080")
          .withEnv("KEYCLOAK_USER", adminUser)
          .withEnv("KEYCLOAK_PASSWORD", adminPassword)
          .withEnv("KEYCLOAK_AVAILABILITYCHECK_ENABLED", "true")
          .withEnv("IMPORT_FILES_LOCATIONS", "file:/config/realm-cell.yaml")
          .withEnv("IMPORT_VARSUBSTITUTION_ENABLED", "true")
          .withEnv("IMPORT_VARSUBSTITUTION_UNDEFINEDISERROR", "true")
          .withEnv("IMPORT_REMOTESTATE_ENABLED", "true")
          .withEnv(env)
          .withCopyFileToContainer(MountableFile.forHostPath(RepoFiles.realmTemplate()), "/config/realm-cell.yaml")
          .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("keycloak-config-cli"))
          .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(4)));
      cli.start();
    }
  }

  @AfterAll
  void stop() {
    if (keycloak != null) {
      keycloak.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  // ------------------------------------------------------------------------------------------
  // Cấu hình realm sau khi nạp
  // ------------------------------------------------------------------------------------------

  @Test
  void realmNapDungCauHinhVaVaiTroTheoMaTran() {
    JsonNode r = admin.get("/admin/realms/" + REALM);
    assertThat(r.path("organizationsEnabled").asBoolean()).isTrue();
    assertThat(r.path("registrationAllowed").asBoolean()).isFalse();
    assertThat(r.path("bruteForceProtected").asBoolean()).isTrue();
    assertThat(r.path("permanentLockout").asBoolean()).isTrue();
    assertThat(r.path("failureFactor").asInt()).isEqualTo(5);
    assertThat(r.path("waitIncrementSeconds").asInt()).isEqualTo(900);
    assertThat(r.path("maxTemporaryLockouts").asInt()).isEqualTo(15);
    assertThat(r.path("defaultSignatureAlgorithm").asText()).isEqualTo("ES256");
    assertThat(r.path("passwordPolicy").asText()).contains("hashAlgorithm(argon2)");
    assertThat(r.path("attributes").path("mission.cell").asText()).isEqualTo(values.path("realm").path("cell").asText());

    Set<String> roles = new HashSet<>();
    admin.get("/admin/realms/" + REALM + "/roles").forEach(x -> roles.add(x.path("name").asText()));
    // Keycloak tự tạo default-roles-<realm>, offline_access, uma_authorization; còn lại phải đúng ma trận
    roles.removeAll(Set.of("default-roles-" + REALM, "offline_access", "uma_authorization"));
    // Vai trò dịch vụ svc_* (DV-02-04) không thuộc ma trận AC
    roles.removeIf(name -> name.startsWith("svc_"));
    assertThat(roles).containsExactlyInAnyOrderElementsOf(RealmTemplateStaticTest.expectedRolesFromMatrix().keySet());

    // Văn bản MSG-00-01, MSG-00-02 cho trang đăng nhập tiếng Việt
    JsonNode vi = admin.get("/admin/realms/" + REALM + "/localization/vi");
    assertThat(vi.path("invalidUserMessage").asText()).startsWith("Tên đăng nhập hoặc mật khẩu không đúng.");
    assertThat(vi.path("accountTemporarilyDisabledMessage").asText()).startsWith("Tài khoản tạm khoá");
  }

  @Test
  void clientScopeNguCanhCoMapperVaGanChoClientNguoiDung() {
    JsonNode scope = null;
    for (JsonNode s : admin.get("/admin/realms/" + REALM + "/client-scopes")) {
      if ("mission-context".equals(s.path("name").asText())) {
        scope = s;
      }
    }
    assertThat(scope).as("client scope mission-context (DV-02-02)").isNotNull();
    assertThat(scope.path("protocolMappers").get(0).path("protocolMapper").asText())
        .isEqualTo("mission-context-mapper");
    for (String clientId : new String[] {"mission-web", "mission-mobile"}) {
      String id = admin.get("/admin/realms/" + REALM + "/clients?clientId=" + clientId).get(0).path("id").asText();
      assertThat(admin.get("/admin/realms/" + REALM + "/clients/" + id + "/default-client-scopes").findValuesAsText(
          "name")).contains("mission-context");
    }
  }

  /**
   * DV-04-07: client credentials của org-service đủ để đọc người dùng và ghi thuộc tính ngữ cảnh (PUT /org/me/context
   * qua Admin API), nhưng không đọc được client hay tạo vai trò (chỉ view-users, manage-users).
   */
  @Test
  void taiKhoanDichVuOrgServiceChiXemVaSuaNguoiDung() {
    String userId = admin.createUser(REALM, "nguoi-dung-ngu-canh", UUID.randomUUID().toString());
    OidcBrowser http = new OidcBrowser(baseUrl, REALM, "org-service", "urn:ietf:wg:oauth:2.0:oob");
    HttpResponse<String> t = http.postForm(http.endpoint("token"),
        Map.of("grant_type", "client_credentials"), "org-service:org-" + apisixSecret);
    assertThat(t.statusCode()).as(t.body()).isEqualTo(200);
    String token = RepoFiles.readYaml(t.body()).path("access_token").asText();
    java.util.function.Function<String, HttpRequest.Builder> req = path -> HttpRequest.newBuilder(
        URI.create(baseUrl + "/admin/realms/" + REALM + path)).header("Authorization", "Bearer " + token);

    HttpResponse<String> user = http.send(req.apply("/users/" + userId).GET().build());
    assertThat(user.statusCode()).isEqualTo(200);
    ObjectNode u = (ObjectNode) RepoFiles.readYaml(user.body());
    u.putObject("attributes").putArray("mission_context_unit_id").add("don-vi-kiem-thu");
    assertThat(http.send(req.apply("/users/" + userId).header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(u.toString())).build()).statusCode()).isEqualTo(204);
    assertThat(admin.get("/admin/realms/" + REALM + "/users/" + userId).path("attributes")
        .path("mission_context_unit_id").get(0).asText()).isEqualTo("don-vi-kiem-thu");

    assertThat(http.send(req.apply("/clients").GET().build()).statusCode()).isEqualTo(403);
    assertThat(http.send(req.apply("/roles").header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"vai-tro-la\"}")).build()).statusCode())
        .isEqualTo(403);
  }

  @Test
  void organizationDaiDienTenantTaoDuoc() {
    ObjectNode org = RepoFiles.JSON.createObjectNode();
    org.put("name", "tenant-kiem-thu");
    org.put("alias", "tenant-kiem-thu");
    org.putArray("domains").addObject().put("name", "tenant-kiem-thu.example.invalid");
    HttpResponse<String> r = admin.post("/admin/realms/" + REALM + "/organizations", org);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
    assertThat(admin.get("/admin/realms/" + REALM + "/organizations").size()).isGreaterThanOrEqualTo(1);

    // Khi đã có Organization, trang đăng nhập chuyển sang hai bước (tên đăng nhập, rồi mật khẩu);
    // đăng nhập Code + PKCE vẫn đạt.
    String password = newPassword();
    admin.createUser(REALM, "sau.organization", password);
    OidcBrowser b = browser(REALM);
    assertThat(b.openLoginPage()).isTrue();
    String code = b.submitLogin("sau.organization", password).orElseThrow(() -> new AssertionError(b.pageSummary()));
    assertThat(b.identityFirstSteps).as("bước nhập mật khẩu riêng").isEqualTo(1);
    assertThat(b.exchange(code, b.codeVerifier).statusCode()).isEqualTo(200);
  }

  // ------------------------------------------------------------------------------------------
  // Bậc xác thực và MFA (DV-02-03, BR-00-01, BR-00-06)
  // ------------------------------------------------------------------------------------------

  /** Cài SmartOTP trên trang bắt buộc cài đặt rồi gửi mã; trả về {@code code}. */
  private static String configureTotp(OidcBrowser b) {
    assertThat(b.hasForm("kc-totp-settings-form")).as(b.pageSummary()).isTrue();
    String secret = b.hiddenValue("totpSecret");
    return b.submitForm("kc-totp-settings-form", Map.of("totp", OidcBrowser.totp(secret), "totpSecret", secret,
        "userLabel", "SmartOTP")).orElseThrow(() -> new AssertionError(b.pageSummary()));
  }

  private static JsonNode accessClaims(OidcBrowser b, String code) {
    HttpResponse<String> tr = b.exchange(code, b.codeVerifier);
    assertThat(tr.statusCode()).as(tr.body()).isEqualTo(200);
    return OidcBrowser.decodeJwt(RepoFiles.readYaml(tr.body()).path("access_token").asText())[1];
  }

  @Test
  void vaiTroAal2KhongVaoDuocChiBangMatKhau() {
    String password = newPassword();
    String userId = admin.createUser(REALM, "tham.dinh", password);
    JsonNode role = admin.get("/admin/realms/" + REALM + "/roles/appraiser");
    assertThat(admin.post("/admin/realms/" + REALM + "/users/" + userId + "/role-mappings/realm",
        RepoFiles.JSON.createArrayNode().add(role)).statusCode()).isEqualTo(204);

    OidcBrowser b = browser(REALM);
    assertThat(b.openLoginPage()).isTrue();
    assertThat(b.submitLogin("tham.dinh", password)).as("chỉ mật khẩu không nhận được code").isEmpty();
    JsonNode claims = accessClaims(b, configureTotp(b));
    assertThat(claims.path("acr").asText()).isEqualTo("aal2");
  }

  @Test
  void thietBiMoiBiHoiOtp() {
    String password = newPassword();
    admin.createUser(REALM, "thiet.bi.moi", password);
    OidcBrowser quen = browser(REALM);
    quen.userAgent = "Mozilla/5.0 (Windows NT 10.0) Trinh-duyet-co-quan/1.0";
    assertThat(quen.openLoginPage()).isTrue();
    String code = quen.submitLogin("thiet.bi.moi", password).orElseThrow(() -> new AssertionError(quen.pageSummary()));
    assertThat(accessClaims(quen, code).path("acr").asText()).isEqualTo("aal1");

    // Trình duyệt khác trên cùng thiết bị (cùng dấu vân tay User-Agent) đăng nhập lại: không hỏi OTP.
    OidcBrowser quenLai = browser(REALM);
    quenLai.userAgent = quen.userAgent;
    assertThat(quenLai.openLoginPage()).isTrue();
    assertThat(quenLai.submitLogin("thiet.bi.moi", password)).isPresent();

    OidcBrowser moi = browser(REALM);
    moi.userAgent = "Mozilla/5.0 (Linux; Android 14) Thiet-bi-la/2.0";
    assertThat(moi.openLoginPage()).isTrue();
    assertThat(moi.submitLogin("thiet.bi.moi", password)).as("thiết bị mới phải qua bậc 2").isEmpty();
    assertThat(moi.hasForm("kc-totp-settings-form") || moi.hasForm("kc-otp-login-form")).as(moi.pageSummary())
        .isTrue();
  }

  @Test
  void xinAal2TraTokenAal2VaAuthTimeMoi() throws InterruptedException {
    String password = newPassword();
    admin.createUser(REALM, "buoc.len", password);
    OidcBrowser b = browser(REALM);
    assertThat(b.openLoginPage()).isTrue();
    String code = b.submitLogin("buoc.len", password).orElseThrow(() -> new AssertionError(b.pageSummary()));
    JsonNode aal1 = accessClaims(b, code);
    assertThat(aal1.path("acr").asText()).isEqualTo("aal1");

    Thread.sleep(1500);
    assertThat(b.authorizeWith(Map.of("acr_values", "aal2"))).as("phải hỏi bậc 2").isEmpty();
    JsonNode aal2 = accessClaims(b, configureTotp(b));
    assertThat(aal2.path("acr").asText()).isEqualTo("aal2");
    assertThat(aal2.path("auth_time").asLong()).isGreaterThan(aal1.path("auth_time").asLong());
    assertThat(aal2.path("sid").asText()).isEqualTo(aal1.path("sid").asText());
  }

  // ------------------------------------------------------------------------------------------
  // Mã khôi phục MFA (DV-02-08, QT-00 ngoại lệ mất SmartOTP)
  // ------------------------------------------------------------------------------------------

  private String userInTenant(String username, String password, String tenant, String role) {
    String id = admin.createUser(REALM, username, password);
    JsonNode u = admin.get("/admin/realms/" + REALM + "/users/" + id);
    ((ObjectNode) u).putObject("attributes").putArray("tenant_id").add(tenant);
    assertThat(admin.put("/admin/realms/" + REALM + "/users/" + id, u).statusCode()).isEqualTo(204);
    if (role != null) {
      admin.post("/admin/realms/" + REALM + "/users/" + id + "/role-mappings/realm",
          RepoFiles.JSON.createArrayNode().add(admin.get("/admin/realms/" + REALM + "/roles/" + role)));
    }
    return id;
  }

  /** Đăng nhập bậc 2 lần đầu (bắt cài SmartOTP); trả token truy cập. */
  private String aal2Token(String username, String password) {
    OidcBrowser b = browser(REALM);
    assertThat(b.authorizeWith(Map.of("acr_values", "aal2"))).isEmpty();
    assertThat(b.submitLogin(username, password)).isEmpty();
    HttpResponse<String> tr = b.exchange(configureTotp(b), b.codeVerifier);
    return RepoFiles.readYaml(tr.body()).path("access_token").asText();
  }

  private static final com.atlassian.oai.validator.OpenApiInteractionValidator PLATFORM =
      com.atlassian.oai.validator.OpenApiInteractionValidator.createForSpecificationUrl(RepoFiles.root()
          .resolve("contracts/openapi/modules/platform.yaml").toUri().toString())
          .withLevelResolver(com.atlassian.oai.validator.report.LevelResolver.create()
              .withLevel("validation.request.security.missing",
                  com.atlassian.oai.validator.report.ValidationReport.Level.IGNORE)
              .build())
          .build();

  /** Gọi API mã khôi phục và đối chiếu cặp yêu cầu, phản hồi với hợp đồng ({@code /api/v1/iam/...}). */
  private HttpResponse<String> iam(String token, String path, String key, String body) {
    HttpResponse<String> res = iamRaw(token, path, key, body);
    var req = com.atlassian.oai.validator.model.SimpleRequest.Builder.post("/api/v1/iam/" + path)
        .withContentType("application/json").withBody(body);
    if (key != null) {
      req.withHeader("Idempotency-Key", key);
    }
    var sb = com.atlassian.oai.validator.model.SimpleResponse.Builder.status(res.statusCode()).withBody(res.body());
    res.headers().firstValue("Content-Type").ifPresent(sb::withContentType);
    var report = PLATFORM.validate(req.build(), sb.build());
    assertThat(report.getMessages()).as(path + " " + res.statusCode() + " " + res.body()).isEmpty();
    return res;
  }

  private HttpResponse<String> iamRaw(String token, String path, String key, String body) {
    HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(baseUrl + "/realms/" + REALM + "/mission-iam/" + path))
        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (key != null) {
      r.header("Idempotency-Key", key);
    }
    return browser(REALM).send(r.build());
  }

  private String execInContainer(String... cmd) throws Exception {
    return keycloak.execInContainer(cmd).getStdout();
  }

  @Test
  void maKhoiPhucCapMotLanDungMotLanThuHoiVaKiemSoatQuyen() throws Exception {
    JsonNode profile = admin.get("/admin/realms/" + REALM + "/users/profile");
    ((ObjectNode) profile).put("unmanagedAttributePolicy", "ADMIN_EDIT");
    admin.put("/admin/realms/" + REALM + "/users/profile", profile);
    String tenant = UUID.randomUUID().toString();
    String pw = newPassword();
    userInTenant("qt.don.vi", pw, tenant, "tenant_admin");
    userInTenant("qt.don.vi.khac", pw, UUID.randomUUID().toString(), "tenant_admin");
    String target = userInTenant("mat.thiet.bi", pw, tenant, null);
    String issue = "users/" + target + "/mfa-recovery-codes";
    String body = "{\"verificationMethod\":\"VAN_BAN_DON_VI\",\"reason\":\"Cán bộ báo mất điện thoại cài SmartOTP\"}";

    // Token AAL1 của quản trị (chỉ mật khẩu, client kiểm thử cho phép direct grant), lấy trước khi cài SmartOTP.
    ObjectNode cli = RepoFiles.JSON.createObjectNode().put("clientId", "kiem-thu-aal1").put("publicClient", true)
        .put("directAccessGrantsEnabled", true).put("standardFlowEnabled", false);
    admin.post("/admin/realms/" + REALM + "/clients", cli);
    HttpResponse<String> direct = browser(REALM).postForm(browser(REALM).endpoint("token"), Map.of("grant_type",
        "password", "client_id", "kiem-thu-aal1", "username", "qt.don.vi", "password", pw), null);
    assertThat(direct.statusCode()).as(direct.body()).isEqualTo(200);
    String aal1 = RepoFiles.readYaml(direct.body()).path("access_token").asText();

    // Người được cấp đã cài SmartOTP trên thiết bị nay bị mất.
    aal2Token("mat.thiet.bi", pw);
    String adminToken = aal2Token("qt.don.vi", pw);
    String otherTenantToken = aal2Token("qt.don.vi.khac", pw);

    // Quản trị ở AAL1 bị yêu cầu bước lên; tenant khác và tự cấp bị 403.
    HttpResponse<String> r = iam(aal1, issue, UUID.randomUUID().toString(), body);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(401);
    assertThat(r.headers().firstValue("WWW-Authenticate").orElse("")).contains("insufficient_user_authentication");
    assertThat(iam(otherTenantToken, issue, UUID.randomUUID().toString(), body).statusCode()).isEqualTo(403);
    String selfId = admin.get("/admin/realms/" + REALM + "/users?exact=true&username=qt.don.vi").get(0).path("id")
        .asText();
    assertThat(iam(adminToken, "users/" + selfId + "/mfa-recovery-codes", UUID.randomUUID().toString(), body)
        .statusCode()).isEqualTo(403);
    assertThat(iamRaw(adminToken, issue, UUID.randomUUID().toString(), "{\"verificationMethod\":\"TRUC_TIEP\",\"reason\":\"ngan\"}")
        .statusCode()).isEqualTo(422);

    // Cấp: mã chỉ ở đúng phản hồi này; gửi lại cùng khoá không trả mã lần nữa.
    String key = UUID.randomUUID().toString();
    r = iam(adminToken, issue, key, body);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
    assertThat(r.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store");
    JsonNode issued = RepoFiles.readYaml(r.body());
    String code = issued.path("recoveryCode").asText();
    assertThat(issued.path("personId").asText()).isEqualTo(target);
    assertThat(java.time.Duration.between(java.time.Instant.parse(issued.path("issuedAt").asText()),
        java.time.Instant.parse(issued.path("expiresAt").asText()))).isEqualTo(java.time.Duration.ofMinutes(15));
    HttpResponse<String> replay = iam(adminToken, issue, key, body);
    assertThat(replay.statusCode()).isEqualTo(409);
    assertThat(replay.body()).doesNotContain(code);

    // Dùng: "Phương thức khác" → mã khôi phục; sai một lần rồi đúng; bắt cài lại SmartOTP.
    String execution = null;
    for (JsonNode e : admin.get("/admin/realms/" + REALM + "/authentication/flows/mission-browser/executions")) {
      if ("mission-recovery-code".equals(e.path("providerId").asText())) {
        execution = e.path("id").asText();
      }
    }
    OidcBrowser b = browser(REALM);
    assertThat(b.authorizeWith(Map.of("acr_values", "aal2"))).isEmpty();
    assertThat(b.submitLogin("mat.thiet.bi", pw)).isEmpty();
    assertThat(b.hasForm("kc-otp-login-form")).as(b.pageSummary()).isTrue();
    assertThat(b.submitForm("kc-select-try-another-way-form", Map.of("tryAnotherWay", "on"))).isEmpty();
    assertThat(b.submitForm("kc-select-credential-form", Map.of("authenticationExecution", execution))).isEmpty();
    assertThat(b.hasForm("kc-recovery-code-form")).as(b.pageSummary()).isTrue();
    assertThat(b.submitForm("kc-recovery-code-form", Map.of("recoveryCode", "2222-2222-2222-2222"))).isEmpty();
    assertThat(b.hasForm("kc-recovery-code-form")).as("sai mã vẫn ở trang nhập mã").isTrue();
    assertThat(b.submitForm("kc-recovery-code-form", Map.of("recoveryCode", code.toLowerCase()))).isEmpty();
    JsonNode claims = accessClaims(b, configureTotp(b));
    assertThat(claims.path("acr").asText()).isEqualTo("aal2");
    List<String> types = new java.util.ArrayList<>();
    admin.get("/admin/realms/" + REALM + "/users/" + target + "/credentials").forEach(c -> types.add(c.path("type")
        .asText()));
    assertThat(types).as("mã đã dùng bị xoá, SmartOTP cũ được thay").doesNotContain("mission-recovery-code")
        .containsOnlyOnce("otp");

    // Dùng lại mã đã dùng bị từ chối.
    OidcBrowser again = browser(REALM);
    assertThat(again.authorizeWith(Map.of("acr_values", "aal2"))).isEmpty();
    assertThat(again.submitLogin("mat.thiet.bi", pw)).isEmpty();
    assertThat(again.submitForm("kc-select-try-another-way-form", Map.of("tryAnotherWay", "on"))).isEmpty();
    // Không còn mã còn hạn: Keycloak không mở bước nhập mã nữa, không phát code.
    assertThat(again.submitForm("kc-select-credential-form", Map.of("authenticationExecution", execution))).isEmpty();
    assertThat(again.hasForm("kc-recovery-code-form")).as(again.pageSummary()).isFalse();

    // Thu hồi.
    assertThat(iam(adminToken, issue, UUID.randomUUID().toString(), body).statusCode()).isEqualTo(201);
    r = iam(adminToken, issue + "/revoke", UUID.randomUUID().toString(), "{\"reason\":\"Cán bộ đã tìm lại thiết bị\"}");
    assertThat(RepoFiles.readYaml(r.body()).path("revokedCount").asInt()).as(r.body()).isEqualTo(1);
    r = iam(adminToken, issue + "/revoke", UUID.randomUUID().toString(), "{\"reason\":\"Thu hồi lần hai\"}");
    assertThat(RepoFiles.readYaml(r.body()).path("revokedCount").asInt()).isZero();

    // Mã không có trong CSDL, nhật ký hay hàng đợi sự kiện; sự kiện cấp, dùng, dùng sai, thu hồi đã được phát.
    String plain = code.replace("-", "");
    assertThat(keycloak.getLogs()).doesNotContain(code).doesNotContain(plain);
    assertThat(execInContainer("sh", "-c", "grep -rlaF -e '" + code + "' -e '" + plain
        + "' /opt/keycloak/data /tmp/mission-events || true")).isBlank();
    String queue = execInContainer("sh", "-c", "cat /tmp/mission-events/queue.log | tr -c '[:print:]' ' '");
    assertThat(queue).contains("MFA_RECOVERY_CODE_ISSUED", "MFA_RECOVERY_CODE_USED", "MFA_RECOVERY_CODE_USE_ERROR",
        "MFA_RECOVERY_CODE_REVOKED", "CAP_MA_KHOI_PHUC_MFA", "BAO_CAP_MA_KHOI_PHUC");
  }

  // ------------------------------------------------------------------------------------------
  // Authorization Code + PKCE (SEC-17)
  // ------------------------------------------------------------------------------------------

  @Test
  void dangNhapCodePkceS256ThanhCongTokenEs256DungThoiHan() {
    String password = newPassword();
    String userId = admin.createUser(REALM, "pkce.thanhcong", password);

    OidcBrowser b = browser(REALM);
    assertThat(b.openLoginPage()).as("trang đăng nhập").isTrue();
    String code = b.submitLogin("pkce.thanhcong", password).orElseThrow(() -> new AssertionError(b.pageSummary()));
    HttpResponse<String> tr = b.exchange(code, b.codeVerifier);
    assertThat(tr.statusCode()).as(tr.body()).isEqualTo(200);
    JsonNode tokens = RepoFiles.readYaml(tr.body());

    JsonNode[] at = OidcBrowser.decodeJwt(tokens.path("access_token").asText());
    JsonNode[] id = OidcBrowser.decodeJwt(tokens.path("id_token").asText());
    JsonNode[] rt = OidcBrowser.decodeJwt(tokens.path("refresh_token").asText());
    long access = values.path("realm").path("tokens").path("accessTokenLifespan").asLong();
    long idle = values.path("realm").path("tokens").path("clientSessionIdleTimeout").asLong();

    assertThat(at[0].path("alg").asText()).isEqualTo("ES256");
    assertThat(id[0].path("alg").asText()).isEqualTo("ES256");
    assertThat(at[1].path("exp").asLong() - at[1].path("iat").asLong()).isEqualTo(access).isEqualTo(300);
    assertThat(tokens.path("expires_in").asLong()).isBetween(access - 5, access);
    // Refresh token trượt theo nhàn rỗi 30 phút (giới hạn trên 8 giờ là clientSessionMaxLifespan)
    assertThat(rt[1].path("exp").asLong() - rt[1].path("iat").asLong()).isEqualTo(idle);
    assertThat(id[1].path("nonce").asText()).isEqualTo(b.nonce);
    assertThat(at[1].path("sub").asText()).isEqualTo(userId);
    assertThat(at[1].path("azp").asText()).isEqualTo("mission-web");

    // Khoá ký công bố ở JWKS là EC P-256
    JsonNode jwks = admin.getPublic("/realms/" + REALM + "/protocol/openid-connect/certs");
    String kid = at[0].path("kid").asText();
    JsonNode key = null;
    for (JsonNode k : jwks.path("keys")) {
      if (kid.equals(k.path("kid").asText())) {
        key = k;
      }
    }
    assertThat(key).as("kid của access token có trong JWKS").isNotNull();
    assertThat(key.path("kty").asText()).isEqualTo("EC");
    assertThat(key.path("crv").asText()).isEqualTo("P-256");

    // Cổng APISIX kiểm token bằng introspection với client riêng
    HttpResponse<String> intro = b.postForm(b.endpoint("token/introspect"),
        Map.of("token", tokens.path("access_token").asText()), "apisix-gateway:" + apisixSecret);
    assertThat(intro.statusCode()).as(intro.body()).isEqualTo(200);
    assertThat(RepoFiles.readYaml(intro.body()).path("active").asBoolean()).isTrue();
  }

  @Test
  void thieuHoacSaiCodeVerifierBiTuChoi() {
    String password = newPassword();
    admin.createUser(REALM, "pkce.thieuverifier", password);

    OidcBrowser b = browser(REALM);
    assertThat(b.openLoginPage()).isTrue();
    String code = b.submitLogin("pkce.thieuverifier", password).orElseThrow(() -> new AssertionError(b.pageSummary()));
    HttpResponse<String> missing = b.exchange(code, null);
    assertThat(missing.statusCode()).isEqualTo(400);
    assertThat(missing.body()).contains("invalid_grant");

    OidcBrowser b2 = browser(REALM);
    assertThat(b2.openLoginPage()).isTrue();
    String code2 = b2.submitLogin("pkce.thieuverifier", password).orElseThrow(() -> new AssertionError(b2.pageSummary()));
    HttpResponse<String> wrong = b2.exchange(code2, OidcBrowser.randomUrlSafe(32));
    assertThat(wrong.statusCode()).isEqualTo(400);
    assertThat(wrong.body()).contains("invalid_grant");
  }

  @Test
  void yeuCauKhongCoCodeChallengeHoacDungPlainBiTuChoi() {
    OidcBrowser b = browser(REALM);
    Map<String, String> p = new java.util.LinkedHashMap<>(b.defaultAuthParams());
    p.remove("code_challenge");
    p.remove("code_challenge_method");
    assertAuthError(b.authorize(p), "invalid_request");

    Map<String, String> plain = new java.util.LinkedHashMap<>(b.defaultAuthParams());
    plain.put("code_challenge", b.codeVerifier);
    plain.put("code_challenge_method", "plain");
    assertAuthError(b.authorize(plain), "invalid_request");
  }

  @Test
  void implicitVaDirectGrantBiTat() {
    OidcBrowser b = browser(REALM);
    Map<String, String> implicit = new java.util.LinkedHashMap<>(b.defaultAuthParams());
    implicit.put("response_type", "token id_token");
    HttpResponse<String> r = b.authorize(implicit);
    assertThat(r.statusCode()).isIn(302, 400);
    if (r.statusCode() == 302) {
      String loc = r.headers().firstValue("Location").orElseThrow();
      assertThat(loc).contains("error=").doesNotContain("access_token=");
    }

    String password = newPassword();
    admin.createUser(REALM, "khong.directgrant", password);
    for (String client : List.of("mission-web", "mission-mobile")) {
      HttpResponse<String> d = b.postForm(b.endpoint("token"), Map.of(
          "grant_type", "password", "client_id", client, "username", "khong.directgrant", "password", password), null);
      assertThat(d.statusCode()).as(client).isIn(400, 401);
      assertThat(d.body()).as(client).contains("unauthorized_client");
    }
    // apisix-gateway không phát token cho chính nó
    HttpResponse<String> cc = b.postForm(b.endpoint("token"), Map.of("grant_type", "client_credentials"),
        "apisix-gateway:" + apisixSecret);
    assertThat(cc.statusCode()).isIn(400, 401);
    assertThat(cc.body()).contains("unauthorized_client");
  }

  @Test
  void refreshTokenXoayVongDungLaiBiTuChoi() {
    String password = newPassword();
    admin.createUser(REALM, "refresh.xoayvong", password);
    OidcBrowser b = browser(REALM);
    assertThat(b.openLoginPage()).isTrue();
    String code = b.submitLogin("refresh.xoayvong", password).orElseThrow(() -> new AssertionError(b.pageSummary()));
    JsonNode t1 = RepoFiles.readYaml(b.exchange(code, b.codeVerifier).body());
    String r1 = t1.path("refresh_token").asText();

    HttpResponse<String> first = b.refresh(r1);
    assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
    String r2 = RepoFiles.readYaml(first.body()).path("refresh_token").asText();
    assertThat(r2).isNotEqualTo(r1);

    HttpResponse<String> reuse = b.refresh(r1);
    assertThat(reuse.statusCode()).isEqualTo(400);
    assertThat(reuse.body()).contains("invalid_grant");
    // Phát hiện dùng lại thì thu hồi cả chuỗi (TL-01 D5): token mới nhất cũng không làm mới được nữa
    HttpResponse<String> after = b.refresh(r2);
    assertThat(after.statusCode()).as(after.body()).isEqualTo(400);
    assertThat(after.body()).contains("invalid_grant");
  }

  // ------------------------------------------------------------------------------------------
  // Chống dò mật khẩu (BR-00-05, TL-04 SEC-14)
  // ------------------------------------------------------------------------------------------

  @Test
  void sauLanSaiThuNamTaiKhoanBiKhoaTamDangNhapDungVanThatBai() throws InterruptedException {
    String password = newPassword();
    String userId = admin.createUser(REALM, "khoa.tam", password);
    OidcBrowser b = browser(REALM);
    assertThat(b.openLoginPage()).isTrue();

    for (int i = 1; i <= 6; i++) {
      // Cách nhau hơn 1 giây để không rơi vào cơ chế chặn đăng nhập dồn dập (quickLoginCheck)
      Thread.sleep(1100);
      assertThat(b.submitLogin("khoa.tam", "sai-mat-khau-" + i)).as("lần sai " + i).isEmpty();
      JsonNode bf = admin.get("/admin/realms/" + REALM + "/attack-detection/brute-force/users/" + userId);
      if (i == 1) {
        // MSG-00-01
        assertThat(b.lastPage).contains("Tên đăng nhập hoặc mật khẩu không đúng.");
      }
      if (i == 4) {
        assertThat(bf.path("disabled").asBoolean()).as("chưa khoá sau 4 lần sai").isFalse();
      }
      if (i >= 5) {
        assertThat(bf.path("disabled").asBoolean()).as("khoá sau lần sai thứ " + i).isTrue();
        assertThat(bf.path("numFailures").asInt()).isEqualTo(5);
      }
    }
    Thread.sleep(1100);
    assertThat(b.submitLogin("khoa.tam", password)).as("mật khẩu đúng khi đang khoá").isEmpty();
    // MSG-00-02
    assertThat(b.lastPage).contains("Tài khoản tạm khoá do đăng nhập sai nhiều lần.");

    // Thử lại ở phiên trình duyệt mới vẫn bị khoá
    OidcBrowser b2 = browser(REALM);
    assertThat(b2.openLoginPage()).isTrue();
    assertThat(b2.submitLogin("khoa.tam", password)).isEmpty();

    // Sự kiện cho DV-02-05 gửi thông báo
    JsonNode events = admin.get("/admin/realms/" + REALM + "/events?type=USER_DISABLED_BY_TEMPORARY_LOCKOUT&user=" + userId);
    assertThat(events.size()).isGreaterThanOrEqualTo(1);
    // Khoá tạm không vô hiệu hoá tài khoản
    assertThat(admin.get("/admin/realms/" + REALM + "/users/" + userId).path("enabled").asBoolean()).isTrue();
  }

  @Test
  void khoaCoDinhOLanSaiThuHaiMuoi() throws InterruptedException {
    String password = newPassword();
    String userId = admin.createUser(FAST_REALM, "khoa.codinh", password);
    OidcBrowser b = browser(FAST_REALM);
    assertThat(b.openLoginPage()).isTrue();
    for (int i = 1; i <= 20; i++) {
      // Trước lần sai thứ 5: cách nhau hơn 1 giây; từ đó: chờ hết khoá tạm để lần sai được tính
      Thread.sleep(i <= 5 ? 1100 : FAST_WAIT_SECONDS * 1000L + 600);
      assertThat(b.submitLogin("khoa.codinh", "sai-mat-khau-" + i)).isEmpty();
      if (i == 19) {
        assertThat(admin.get("/admin/realms/" + FAST_REALM + "/users/" + userId).path("enabled").asBoolean())
            .as("còn hiệu lực sau 19 lần sai").isTrue();
      }
    }
    assertThat(admin.get("/admin/realms/" + FAST_REALM + "/users/" + userId).path("enabled").asBoolean())
        .as("khoá cố định sau 20 lần sai").isFalse();
    JsonNode events = admin.get("/admin/realms/" + FAST_REALM + "/events?type=USER_DISABLED_BY_PERMANENT_LOCKOUT&user=" + userId);
    assertThat(events.size()).isEqualTo(1);

    Thread.sleep(FAST_WAIT_SECONDS * 1000L + 600);
    OidcBrowser b2 = browser(FAST_REALM);
    assertThat(b2.openLoginPage()).isTrue();
    assertThat(b2.submitLogin("khoa.codinh", password)).as("mật khẩu đúng sau khoá cố định").isEmpty();
  }

  // ------------------------------------------------------------------------------------------
  // Mật khẩu (TL-04 SEC-15, NFR-08)
  // ------------------------------------------------------------------------------------------

  @Test
  void matKhauBamArgon2VaChinhSachDoDai() {
    String userId = admin.createUser(REALM, "matkhau.argon2", newPassword());
    assertThat(admin.passwordAlgorithm(REALM, userId)).isEqualTo("argon2");

    HttpResponse<String> shortPw = admin.resetPassword(REALM, userId, "Ngan@123");
    assertThat(shortPw.statusCode()).as("mật khẩu dưới 12 ký tự").isEqualTo(400);
    HttpResponse<String> sameAsUser = admin.resetPassword(REALM, userId, "matkhau.argon2");
    assertThat(sameAsUser.statusCode()).as("mật khẩu trùng tên đăng nhập").isEqualTo(400);
  }

  @Test
  void matKhauBamThuatToanCuDuocBamLaiArgon2KhiDangNhapLanDau() {
    String realmPath = "/admin/realms/" + FAST_REALM;
    ObjectNode rep = (ObjectNode) admin.get(realmPath);
    String policy = rep.path("passwordPolicy").asText();
    String password = newPassword();
    String userId;
    try {
      // Giả lập mật khẩu mang thuật toán cũ: tạm đặt chính sách PBKDF2-SHA512 khi tạo tài khoản
      ObjectNode legacy = RepoFiles.JSON.createObjectNode();
      legacy.put("passwordPolicy", "hashAlgorithm(pbkdf2-sha512) and hashIterations(210000)");
      assertThat(admin.put(realmPath, legacy).statusCode()).isEqualTo(204);
      userId = admin.createUser(FAST_REALM, "matkhau.bamlai", password);
      assertThat(admin.passwordAlgorithm(FAST_REALM, userId)).isEqualTo("pbkdf2-sha512");
    } finally {
      ObjectNode restore = RepoFiles.JSON.createObjectNode();
      restore.put("passwordPolicy", policy);
      assertThat(admin.put(realmPath, restore).statusCode()).isEqualTo(204);
    }
    OidcBrowser b = browser(FAST_REALM);
    assertThat(b.openLoginPage()).isTrue();
    assertThat(b.submitLogin("matkhau.bamlai", password)).isPresent();
    assertThat(admin.passwordAlgorithm(FAST_REALM, userId)).isEqualTo("argon2");
  }

  // ------------------------------------------------------------------------------------------

  private OidcBrowser browser(String realm) {
    return new OidcBrowser(baseUrl, realm, "mission-web", RepoFiles.WEB_REDIRECT);
  }

  private static String newPassword() {
    return "Kt-" + UUID.randomUUID();
  }

  private static void assertAuthError(HttpResponse<String> r, String error) {
    if (r.statusCode() == 302) {
      String loc = r.headers().firstValue("Location").orElseThrow();
      assertThat(OidcBrowser.query(URI.create(loc)).get("error")).as(loc).isEqualTo(error);
      assertThat(loc).doesNotContain("code=");
    } else {
      assertThat(r.statusCode()).isEqualTo(400);
      assertThat(r.body()).doesNotContain("kc-form-login");
    }
  }

  /** Lời gọi Admin REST API của Keycloak (chỉ dùng trong kiểm thử). */
  static final class KeycloakAdmin {
    private final OidcBrowser http;
    private final String baseUrl;
    private final String user;
    private final String password;

    KeycloakAdmin(String baseUrl, String user, String password) {
      this.baseUrl = baseUrl;
      this.user = user;
      this.password = password;
      this.http = new OidcBrowser(baseUrl, "master", "admin-cli", "urn:ietf:wg:oauth:2.0:oob");
    }

    private String token() {
      HttpResponse<String> r = http.postForm(http.endpoint("token"), Map.of(
          "grant_type", "password", "client_id", "admin-cli", "username", user, "password", password), null);
      if (r.statusCode() != 200) {
        throw new IllegalStateException("Không lấy được token quản trị: " + r.body());
      }
      return RepoFiles.readYaml(r.body()).path("access_token").asText();
    }

    private HttpRequest.Builder req(String path) {
      return HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer " + token());
    }

    JsonNode get(String path) {
      HttpResponse<String> r = http.send(req(path).GET().build());
      if (r.statusCode() != 200) {
        throw new IllegalStateException("GET " + path + " → " + r.statusCode() + " " + r.body());
      }
      return RepoFiles.readYaml(r.body());
    }

    JsonNode getPublic(String path) {
      return RepoFiles.readYaml(http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build()).body());
    }

    HttpResponse<String> post(String path, JsonNode body) {
      return http.send(req(path).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }

    HttpResponse<String> put(String path, JsonNode body) {
      return http.send(req(path).header("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }

    String createUser(String realm, String username, String pw) {
      ObjectNode u = RepoFiles.JSON.createObjectNode();
      u.put("username", username);
      u.put("enabled", true);
      u.put("email", username + "@kiem-thu.example.invalid");
      u.put("emailVerified", true);
      u.put("firstName", "Kiểm");
      u.put("lastName", "Thử");
      ObjectNode c = u.putArray("credentials").addObject();
      c.put("type", "password");
      c.put("value", pw);
      c.put("temporary", false);
      HttpResponse<String> r = post("/admin/realms/" + realm + "/users", u);
      if (r.statusCode() != 201) {
        throw new IllegalStateException("Tạo người dùng lỗi " + r.statusCode() + ": " + r.body());
      }
      String loc = r.headers().firstValue("Location").orElseThrow();
      return loc.substring(loc.lastIndexOf('/') + 1);
    }

    HttpResponse<String> resetPassword(String realm, String userId, String pw) {
      ObjectNode c = RepoFiles.JSON.createObjectNode();
      c.put("type", "password");
      c.put("value", pw);
      c.put("temporary", false);
      return put("/admin/realms/" + realm + "/users/" + userId + "/reset-password", c);
    }

    String passwordAlgorithm(String realm, String userId) {
      for (JsonNode c : get("/admin/realms/" + realm + "/users/" + userId + "/credentials")) {
        if ("password".equals(c.path("type").asText())) {
          return RepoFiles.readYaml(c.path("credentialData").asText()).path("algorithm").asText();
        }
      }
      return Optional.<String>empty().orElseThrow();
    }
  }
}
