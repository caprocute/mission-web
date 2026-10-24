package vn.viettel.okrkpi.iam.realm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Môi giới VNeID (DV-02-04) trên Keycloak 26 với mẫu realm production, VNeID giả lập (YC-PT-13) và org-service giả.
 *
 * Truy vết: QT-00 bước 3 → SCR-0001 (nút VNeID) → OIDC /realms/<realm>/broker/vneid/* → SVC-02 → org-service
 * {@code /internal/v1/org/tenants/{t}/persons/national-id-match}; BR-00-03, BR-00-04, MSG-00-03, MSG-00-04.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VneidBrokerKeycloakIT {

  private static final Logger LOG = LoggerFactory.getLogger(VneidBrokerKeycloakIT.class);

  static final String REALM = "okrkpi-vneid";
  static final String TENANT = UUID.randomUUID().toString();
  static final String MSG_LEVEL = "chưa ở mức 2";
  static final String MSG_UNAVAILABLE = "Dịch vụ VNeID đang gián đoạn";
  static final String MSG_NOT_LINKED = "Chưa tìm thấy hồ sơ cán bộ khớp";
  static final String MSG_ALREADY_LINKED = "đã được liên kết với một danh tính khác";
  static final String MSG_SUSPENDED = "đang tạm ngừng sử dụng nền tảng";

  private final String adminUser = "kc-admin-kiem-thu";
  private final String adminPassword = UUID.randomUUID().toString();
  private final String secret = UUID.randomUUID().toString();

  private VneidGiaLap vneid;
  private Network network;
  private GenericContainer<?> keycloak;
  private String baseUrl;
  private RealmTemplateKeycloakIT.KeycloakAdmin admin;

  @BeforeAll
  void start() {
    vneid = new VneidGiaLap();
    network = Network.newNetwork();
    byte[] linkKey = new byte[32];
    new SecureRandom().nextBytes(linkKey);
    keycloak = new GenericContainer<>(System.getProperty("keycloak.image", "keycloak/keycloak:26.4.0"))
        .withNetwork(network)
        .withNetworkAliases("keycloak")
        .withExposedPorts(8080)
        .withExtraHost(VneidGiaLap.HOST_ALIAS, "host-gateway")
        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", adminUser)
        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", adminPassword)
        .withCopyFileToContainer(MountableFile.forHostPath(java.nio.file.Path.of(System.getProperty("provider.jar",
            "../keycloak-events/target/mission-keycloak-events.jar"))), "/opt/keycloak/providers/mission-keycloak-events.jar")
        .withEnv("MISSION_EVENTS_QUEUE_DIR", "/tmp/mission-events")
        .withEnv("MISSION_VNEID_LINK_KEY", Base64.getEncoder().encodeToString(linkKey))
        .withEnv("MISSION_ORG_SERVICE_URL", vneid.orgUrl())
        .withEnv("MISSION_IAM_BROKER_TOKEN_URL",
            "http://localhost:8080/realms/" + REALM + "/protocol/openid-connect/token")
        .withEnv("MISSION_IAM_BROKER_CLIENT_SECRET", "broker-" + secret)
        // Bước kiểm trạng thái tenant (TL-04 mục 11.4) gọi tenant-registry giả; token lấy từ chính realm.
        .withEnv("MISSION_TENANT_REGISTRY_URL", vneid.registryUrl())
        .withEnv("MISSION_TENANT_REGISTRY_TOKEN_URL",
            "http://localhost:8080/realms/" + REALM + "/protocol/openid-connect/token")
        .withEnv("MISSION_TENANT_REGISTRY_CLIENT_ID", "mission-iam-broker")
        .withEnv("MISSION_TENANT_REGISTRY_CLIENT_SECRET", "broker-" + secret)
        .withCommand("start-dev")
        .waitingFor(Wait.forHttp("/realms/master").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));
    keycloak.start();
    baseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    admin = new RealmTemplateKeycloakIT.KeycloakAdmin(baseUrl, adminUser, adminPassword);
    importRealm(RepoFiles.envFromChartValues(REALM, secret, Map.of(
        "MISSION_SSL_REQUIRED", "external",
        "MISSION_VNEID_ENABLED", "true",
        "MISSION_VNEID_ISSUER", vneid.issuer(),
        "MISSION_VNEID_AUTHORIZATION_URL", vneid.issuer() + "/authorize",
        "MISSION_VNEID_TOKEN_URL", vneid.issuer() + "/token",
        "MISSION_VNEID_JWKS_URL", vneid.issuer() + "/jwks",
        "MISSION_VNEID_CLIENT_ID", VneidGiaLap.CLIENT_ID,
        "MISSION_VNEID_CLIENT_SECRET", vneid.clientSecret)));
  }

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
  void stop() throws java.io.IOException {
    if (keycloak != null) {
      // Nhật ký Keycloak để xem khi kiểm thử đỏ (thư mục target, không commit).
      java.nio.file.Files.writeString(java.nio.file.Path.of("target", "vneid-keycloak.log"), keycloak.getLogs());
      keycloak.stop();
    }
    if (network != null) {
      network.close();
    }
    if (vneid != null) {
      vneid.close();
    }
  }

  // ------------------------------------------------------------------------------------------
  // Tiện ích
  // ------------------------------------------------------------------------------------------

  /** Tạo người (tài khoản Keycloak có id là mã người, như user-sync của DV-02-06) và ghi số định danh vào org giả. */
  private String person(String nationalId) {
    return person(nationalId, TENANT);
  }

  private String person(String nationalId, String tenant) {
    String id = UUID.randomUUID().toString();
    ObjectNode body = RepoFiles.JSON.createObjectNode();
    body.put("ifResourceExists", "FAIL");
    ObjectNode u = body.putArray("users").addObject();
    u.put("id", id);
    u.put("username", "nguoi-" + id.substring(0, 8));
    u.put("enabled", true);
    u.put("email", "nguoi-" + id.substring(0, 8) + "@kiem-thu.example.invalid");
    u.put("emailVerified", true);
    u.put("firstName", "Cán bộ");
    u.put("lastName", "Giả lập");
    u.putObject("attributes").putArray("tenant_id").add(tenant);
    HttpResponse<String> r = admin.post("/admin/realms/" + REALM + "/partialImport", body);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    vneid.persons.put(tenant + "|" + nationalId, id);
    return id;
  }

  private static String nid() {
    return String.format("%012d", Math.abs(new SecureRandom().nextLong()) % 1_000_000_000_000L);
  }

  private OidcBrowser browser() {
    return new OidcBrowser(baseUrl, REALM, "mission-web", RepoFiles.WEB_REDIRECT);
  }

  /** Bấm "Đăng nhập bằng VNeID": đi qua Keycloak, VNeID giả, quay về Keycloak; trả code nếu về ứng dụng. */
  private Optional<String> loginWithVneid(OidcBrowser b, VneidGiaLap.Account account) {
    return loginWithVneid(b, account, TENANT);
  }

  private Optional<String> loginWithVneid(OidcBrowser b, VneidGiaLap.Account account, String tenant) {
    vneid.next = account;
    Map<String, String> p = b.defaultAuthParams();
    p.put("kc_idp_hint", "vneid");
    p.put("tenant_id", tenant);
    return follow(b, b.authorize(p));
  }

  private Optional<String> follow(OidcBrowser b, HttpResponse<String> r) {
    for (int i = 0; i < 20 && (r.statusCode() == 302 || r.statusCode() == 303); i++) {
      String loc = r.headers().firstValue("Location").orElseThrow();
      if (loc.startsWith(RepoFiles.WEB_REDIRECT)) {
        return Optional.ofNullable(OidcBrowser.query(URI.create(loc)).get("code"));
      }
      loc = loc.startsWith("/") ? baseUrl + loc : loc.replace(VneidGiaLap.HOST_ALIAS, "localhost");
      r = b.send(HttpRequest.newBuilder(URI.create(loc)).header("Accept-Language", "vi").GET().build());
    }
    b.lastPage = r.body();
    b.lastStatus = r.statusCode();
    return Optional.empty();
  }

  private JsonNode tokens(OidcBrowser b, String code) {
    HttpResponse<String> r = b.exchange(code, b.codeVerifier);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return RepoFiles.readYaml(r.body());
  }

  private int userCount() {
    return admin.get("/admin/realms/" + REALM + "/users/count").asInt();
  }

  private JsonNode federated(String userId) {
    return admin.get("/admin/realms/" + REALM + "/users/" + userId + "/federated-identity");
  }

  // ------------------------------------------------------------------------------------------
  // Kiểm thử
  // ------------------------------------------------------------------------------------------

  @Test
  @Order(1)
  void cauHinhMoiGioiBatPkceNonceKhongLuuToken() {
    JsonNode idp = admin.get("/admin/realms/" + REALM + "/identity-provider/instances/vneid");
    assertThat(idp.path("providerId").asText()).isEqualTo("mission-vneid");
    assertThat(idp.path("enabled").asBoolean()).isTrue();
    assertThat(idp.path("storeToken").asBoolean()).isFalse();
    assertThat(idp.path("config").path("pkceEnabled").asText()).isEqualTo("true");
    assertThat(idp.path("config").path("pkceMethod").asText()).isEqualTo("S256");
    assertThat(idp.path("config").path("disableNonce").asText()).isEqualTo("false");
  }

  @Test
  @Order(2)
  void thieuStateBiTuChoi() {
    OidcBrowser b = browser();
    HttpResponse<String> r = b.send(HttpRequest.newBuilder(URI.create(baseUrl + "/realms/" + REALM
        + "/broker/vneid/endpoint?code=" + UUID.randomUUID())).GET().build());
    assertThat(r.statusCode()).isGreaterThanOrEqualTo(400);
    assertThat(r.headers().firstValue("Location")).isEmpty();
    // state giả mạo kèm code: Keycloak chặn trước khi gọi VNeID, không tính vào bộ ngắt mạch.
    HttpResponse<String> forged = b.send(HttpRequest.newBuilder(URI.create(baseUrl + "/realms/" + REALM
        + "/broker/vneid/endpoint?code=x&state=" + UUID.randomUUID())).GET().build());
    assertThat(forged.statusCode()).isGreaterThanOrEqualTo(400);
    assertThat(forged.body()).doesNotContain(MSG_UNAVAILABLE);
  }

  @Test
  @Order(3)
  void nonceSaiBiTuChoi() {
    String n = nid();
    person(n);
    vneid.nonceOverride = OidcBrowser.randomUrlSafe(16);
    try {
      OidcBrowser b = browser();
      assertThat(loginWithVneid(b, new VneidGiaLap.Account(UUID.randomUUID().toString(), "2", n))).isEmpty();
      assertThat(b.lastStatus).as(b.pageSummary()).isEqualTo(400);
    } finally {
      vneid.nonceOverride = null;
    }
  }

  @Test
  @Order(4)
  void mucMotBiChanVoiThongBaoNangMuc() {
    String n = nid();
    String p = person(n);
    OidcBrowser b = browser();
    assertThat(loginWithVneid(b, new VneidGiaLap.Account(UUID.randomUUID().toString(), "1", n))).isEmpty();
    assertThat(b.lastPage).as(b.pageSummary()).contains(MSG_LEVEL);
    assertThat(federated(p)).isEmpty();
    // Thiếu claim mức cũng bị chặn.
    OidcBrowser c = browser();
    assertThat(loginWithVneid(c, new VneidGiaLap.Account(UUID.randomUUID().toString(), null, n))).isEmpty();
    assertThat(c.lastPage).contains(MSG_LEVEL);
  }

  @Test
  @Order(5)
  void mucHaiKhopNguoiThiLienKetVaDatBacHai() {
    String n = nid();
    String p = person(n);
    String sub = UUID.randomUUID().toString();
    int before = userCount();
    int pkce = vneid.pkceVerified.get();

    OidcBrowser b = browser();
    String code = loginWithVneid(b, new VneidGiaLap.Account(sub, "2", n)).orElseThrow(
        () -> new AssertionError(b.pageSummary()));
    JsonNode at = OidcBrowser.decodeJwt(tokens(b, code).path("access_token").asText())[1];
    assertThat(at.path("sub").asText()).isEqualTo(p);
    assertThat(at.path("acr").asText()).isEqualTo("aal2");
    assertThat(userCount()).isEqualTo(before);
    assertThat(vneid.pkceVerified.get()).isEqualTo(pkce + 1);
    assertThat(vneid.challengeMethods).allMatch("S256"::equals);

    JsonNode link = federated(p);
    assertThat(link).hasSize(1);
    assertThat(link.get(0).path("identityProvider").asText()).isEqualTo("vneid");
    String hashed = link.get(0).path("userId").asText();
    assertThat(hashed).matches("[0-9a-f]{32}").isNotEqualTo(sub);
    assertThat(link.get(0).path("userName").asText()).isEqualTo("vneid." + hashed);

    // Lần đăng nhập sau đi luồng sau môi giới, vẫn đạt bậc 2.
    OidcBrowser again = browser();
    String code2 = loginWithVneid(again, new VneidGiaLap.Account(sub, "2", n)).orElseThrow(
        () -> new AssertionError(again.pageSummary()));
    JsonNode at2 = OidcBrowser.decodeJwt(tokens(again, code2).path("access_token").asText())[1];
    assertThat(at2.path("sub").asText()).isEqualTo(p);
    assertThat(at2.path("acr").asText()).isEqualTo("aal2");

    // Không còn số định danh ở thuộc tính người dùng.
    JsonNode user = admin.get("/admin/realms/" + REALM + "/users/" + p);
    assertThat(user.toString()).doesNotContain(n).doesNotContain(sub);
  }

  @Test
  @Order(6)
  void khongKhopThiKhongTaoTaiKhoan() {
    int before = userCount();
    OidcBrowser b = browser();
    assertThat(loginWithVneid(b, new VneidGiaLap.Account(UUID.randomUUID().toString(), "2", nid()))).isEmpty();
    assertThat(b.lastPage).as(b.pageSummary()).contains(MSG_NOT_LINKED);
    assertThat(userCount()).isEqualTo(before);
  }

  @Test
  @Order(7)
  void lienKetTrungBiChanVaKiemToan() {
    String n = nid();
    String p = person(n);
    OidcBrowser first = browser();
    assertThat(loginWithVneid(first, new VneidGiaLap.Account(UUID.randomUUID().toString(), "2", n)))
        .as(first.pageSummary()).isPresent();

    // Một tài khoản VNeID khác mang cùng số định danh: người đã liên kết, không được gắn thêm (BR-00-04).
    OidcBrowser second = browser();
    assertThat(loginWithVneid(second, new VneidGiaLap.Account(UUID.randomUUID().toString(), "2", n))).isEmpty();
    assertThat(second.lastPage).as(second.pageSummary()).contains(MSG_ALREADY_LINKED);
    assertThat(federated(p)).hasSize(1);

    JsonNode events = admin.get("/admin/realms/" + REALM + "/events?type=IDENTITY_PROVIDER_LINK_ACCOUNT_ERROR&max=50");
    boolean audited = false;
    for (JsonNode e : events) {
      audited |= "vneid_link_conflict".equals(e.path("error").asText()) && p.equals(e.path("userId").asText());
    }
    assertThat(audited).as(events.toString()).isTrue();
  }

  @Test
  @Order(8)
  void khongCoSoDinhDanhRoTrongNhatKy() {
    String logs = keycloak.getLogs();
    for (String key : vneid.persons.keySet()) {
      assertThat(logs).doesNotContain(key.substring(key.indexOf('|') + 1));
    }
    assertThat(vneid.orgCalls).isNotEmpty().allMatch(TENANT::equals);
  }

  @Test
  @Order(20)
  void vneidQuaHanThiNgatMachVaHienThongBao() {
    String path = "/admin/realms/" + REALM + "/identity-provider/instances/vneid";
    ObjectNode idp = (ObjectNode) admin.get(path);
    ((ObjectNode) idp.path("config")).put("missionTimeoutMillis", "1000").put("missionBreakerFailures", "2");
    HttpResponse<String> put = admin.put(path, idp);
    assertThat(put.statusCode()).as(put.body()).isEqualTo(204);
    String n = nid();
    person(n);
    vneid.tokenDelayMillis = 3000;
    try {
      for (int i = 0; i < 2; i++) {
        OidcBrowser b = browser();
        assertThat(loginWithVneid(b, new VneidGiaLap.Account(UUID.randomUUID().toString(), "2", n))).isEmpty();
        assertThat(b.lastPage).as(b.pageSummary()).contains(MSG_UNAVAILABLE);
      }
      int calls = vneid.authorizeCalls.get();
      OidcBrowser open = browser();
      assertThat(loginWithVneid(open, new VneidGiaLap.Account(UUID.randomUUID().toString(), "2", n))).isEmpty();
      assertThat(open.lastPage).as(open.pageSummary()).contains(MSG_UNAVAILABLE);
      assertThat(vneid.authorizeCalls.get()).as("mạch mở thì không chuyển sang VNeID").isEqualTo(calls);
    } finally {
      vneid.tokenDelayMillis = 0;
    }
  }

  // ------------------------------------------------------------------------------------------
  // Tenant tạm ngừng (TL-04 mục 11.4, MSG-00-07): chặn đăng nhập ở Keycloak, quản trị nền tảng vẫn vào được
  // ------------------------------------------------------------------------------------------

  /** Người đăng nhập bằng mật khẩu thuộc tenant {@code tenant}; thêm vai trò realm nếu có. */
  private String passwordUser(String username, String password, String tenant, String role) {
    // Nạp như user-sync (partialImport) để thuộc tính tenant_id được giữ cùng mật khẩu.
    String id = UUID.randomUUID().toString();
    ObjectNode body = RepoFiles.JSON.createObjectNode();
    body.put("ifResourceExists", "FAIL");
    ObjectNode u = body.putArray("users").addObject();
    u.put("id", id);
    u.put("username", username);
    u.put("enabled", true);
    u.put("email", username + "@kiem-thu.example.invalid");
    u.put("emailVerified", true);
    u.put("firstName", "Kiểm");
    u.put("lastName", "Thử");
    u.putObject("attributes").putArray("tenant_id").add(tenant);
    ObjectNode c = u.putArray("credentials").addObject();
    c.put("type", "password");
    c.put("value", password);
    c.put("temporary", false);
    HttpResponse<String> r = admin.post("/admin/realms/" + REALM + "/partialImport", body);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    if (role != null) {
      assertThat(admin.post("/admin/realms/" + REALM + "/users/" + id + "/role-mappings/realm",
          RepoFiles.JSON.createArrayNode().add(admin.get("/admin/realms/" + REALM + "/roles/" + role)))
          .statusCode()).isEqualTo(204);
    }
    return id;
  }

  /** Đăng nhập bằng mật khẩu; nếu bị bắt cài SmartOTP (vai trò bậc 2) thì cài rồi gửi mã. */
  private static Optional<String> passwordLogin(OidcBrowser b, String username, String password) {
    assertThat(b.openLoginPage()).as(b.pageSummary()).isTrue();
    Optional<String> code = b.submitLogin(username, password);
    if (code.isEmpty() && b.hasForm("kc-totp-settings-form")) {
      String totpSecret = b.hiddenValue("totpSecret");
      code = b.submitForm("kc-totp-settings-form", Map.of("totp", OidcBrowser.totp(totpSecret),
          "totpSecret", totpSecret, "userLabel", "SmartOTP"));
    }
    return code;
  }

  private static String password() {
    return "Kt-" + UUID.randomUUID();
  }

  @Test
  @Order(10)
  void tenantDangVanHanhDangNhapDuoc() {
    String tenant = UUID.randomUUID().toString();
    String pw = password();
    passwordUser("van.hanh", pw, tenant, null);
    OidcBrowser b = browser();
    String code = passwordLogin(b, "van.hanh", pw).orElseThrow(() -> new AssertionError(b.pageSummary()));
    assertThat(tokens(b, code).path("access_token").asText()).isNotBlank();
    assertThat(vneid.registryCalls).contains(tenant);
  }

  @Test
  @Order(11)
  void tenantTamNgungKhongDangNhapDuocVaGhiSuKien() {
    String tenant = UUID.randomUUID().toString();
    vneid.tenantStatus.put(tenant, "TAM_NGUNG");
    String pw = password();
    String id = passwordUser("tam.ngung", pw, tenant, null);
    OidcBrowser b = browser();
    assertThat(passwordLogin(b, "tam.ngung", pw)).as("không nhận được code").isEmpty();
    assertThat(b.lastPage).as(b.pageSummary()).contains(MSG_SUSPENDED);
    assertThat(b.lastPage).doesNotContain("TAM_NGUNG");

    JsonNode events = admin.get("/admin/realms/" + REALM + "/events?type=LOGIN_ERROR&user=" + id + "&max=20");
    boolean audited = false;
    for (JsonNode e : events) {
      audited |= "tenant_suspended".equals(e.path("error").asText())
          && "TAM_NGUNG".equals(e.path("details").path("tenant_status").asText());
    }
    assertThat(audited).as(events.toString()).isTrue();

    // Tenant đã chấm dứt cũng bị chặn.
    String ended = UUID.randomUUID().toString();
    vneid.tenantStatus.put(ended, "DA_CHAM_DUT");
    passwordUser("da.cham.dut", pw, ended, null);
    OidcBrowser c = browser();
    assertThat(passwordLogin(c, "da.cham.dut", pw)).isEmpty();
    assertThat(c.lastPage).as(c.pageSummary()).contains(MSG_SUSPENDED);
  }

  @Test
  @Order(12)
  void quanTriNenTangVanDangNhapDuocKhiTenantTamNgung() {
    String tenant = UUID.randomUUID().toString();
    vneid.tenantStatus.put(tenant, "TAM_NGUNG");
    String pw = password();
    passwordUser("quan.tri.nen.tang", pw, tenant, "platform_admin");
    OidcBrowser b = browser();
    String code = passwordLogin(b, "quan.tri.nen.tang", pw).orElseThrow(() -> new AssertionError(b.pageSummary()));
    assertThat(tokens(b, code).path("access_token").asText()).isNotBlank();
  }

  @Test
  @Order(13)
  void phienSsoCuBiChanSauKhiTamNgung() throws InterruptedException {
    String tenant = UUID.randomUUID().toString();
    String pw = password();
    passwordUser("phien.cu", pw, tenant, null);
    OidcBrowser b = browser();
    assertThat(passwordLogin(b, "phien.cu", pw)).as(b.pageSummary()).isPresent();
    assertThat(b.authorizeWith(Map.of())).as("còn phiên SSO thì vào thẳng").isPresent();

    vneid.tenantStatus.put(tenant, "TAM_NGUNG");
    // Trạng thái được đệm 30 giây ở Keycloak (TenantStatusLookup.CACHE_SECONDS).
    Thread.sleep(31_000);
    assertThat(b.authorizeWith(Map.of())).as("phiên SSO không qua được bước kiểm tenant").isEmpty();
    assertThat(b.lastPage).as(b.pageSummary()).contains(MSG_SUSPENDED);

    // Mở lại tenant: đăng nhập lại được (sau hạn đệm).
    vneid.tenantStatus.put(tenant, "DANG_VAN_HANH");
    Thread.sleep(31_000);
    OidcBrowser again = browser();
    assertThat(passwordLogin(again, "phien.cu", pw)).as(again.pageSummary()).isPresent();
  }

  /** Chạy trước ca ngắt mạch VNeID (Order 20) và sau ca kiểm nhật ký (Order 8, chỉ gọi org-service cho TENANT). */
  @Test
  @Order(9)
  void vneidCuaTenantTamNgungBiChan() {
    String tenant = UUID.randomUUID().toString();
    String n = nid();
    String sub = UUID.randomUUID().toString();
    person(n, tenant);
    OidcBrowser first = browser();
    assertThat(loginWithVneid(first, new VneidGiaLap.Account(sub, "2", n), tenant)).as(first.pageSummary())
        .isPresent();

    // Tenant khác (chưa đệm) bị tạm ngừng ngay từ lần liên kết đầu.
    String paused = UUID.randomUUID().toString();
    vneid.tenantStatus.put(paused, "TAM_NGUNG");
    String m = nid();
    person(m, paused);
    OidcBrowser b = browser();
    assertThat(loginWithVneid(b, new VneidGiaLap.Account(UUID.randomUUID().toString(), "2", m), paused)).isEmpty();
    assertThat(b.lastPage).as(b.pageSummary()).contains(MSG_SUSPENDED);
  }

  @Test
  @Order(15)
  void registryGianDoanThiKhongChanDangNhap() {
    String tenant = UUID.randomUUID().toString();
    vneid.tenantStatus.put(tenant, "LOI_REGISTRY");
    String pw = password();
    passwordUser("registry.loi", pw, tenant, null);
    OidcBrowser b = browser();
    assertThat(passwordLogin(b, "registry.loi", pw)).as(b.pageSummary()).isPresent();
  }
}
