package vn.viettel.okrkpi.iam.realm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Kiểm tra tĩnh mẫu realm production (DV-02-01), không cần Keycloak:
 * vai trò realm so khớp docs/phan-quyen/ma-tran.json, client đúng SEC-17, không có bí mật,
 * mọi biến của mẫu đều được Job Helm truyền vào.
 */
class RealmTemplateStaticTest {

  private static final String TEMPLATE = RepoFiles.read(RepoFiles.realmTemplate());

  private static JsonNode realm() {
    return RepoFiles.readYaml(RepoFiles.substitute(TEMPLATE,
        RepoFiles.envFromChartValues("okrkpi", "gia-tri-kiem-thu", Map.of())));
  }

  /** Vai trò mong đợi lấy từ ma trận, cùng quy tắc với docs/phan-quyen/check_realm.py của WEB-056. */
  static Map<String, String> expectedRolesFromMatrix() {
    JsonNode m = RepoFiles.readJson(RepoFiles.matrix());
    Map<String, String> expected = new LinkedHashMap<>();
    Pattern sub = Pattern.compile("vai trò con `([a-z_]+)`");
    for (JsonNode v : m.path("vai_tro")) {
      String role = v.path("role").asText();
      String ac = v.path("ac").asText();
      // AC-11 là client credentials, không phải realm role
      if (role.matches("[a-z_]+")) {
        expected.put(role, ac);
      }
      Matcher mm = sub.matcher(v.path("keycloak").asText());
      while (mm.find()) {
        expected.put(mm.group(1), ac);
      }
    }
    return expected;
  }

  @Test
  void vaiTroRealmKhopMaTranPhanQuyen() {
    Map<String, String> expected = expectedRolesFromMatrix();
    Map<String, JsonNode> actual = new LinkedHashMap<>();
    realm().path("roles").path("realm").forEach(r -> actual.put(r.path("name").asText(), r));
    // Vai trò dịch vụ svc_* (DV-02-04) không thuộc ma trận AC và không gán cho người.
    actual.keySet().removeIf(name -> name.startsWith("svc_"));

    assertThat(actual.keySet()).as("realm role trong mẫu so với ma-tran.json")
        .containsExactlyInAnyOrderElementsOf(expected.keySet());
    // Mười một vai trò AC có realm role (AC-11 là client credentials) cộng một vai trò con của AC-01
    assertThat(expected.values().stream().distinct().count()).isEqualTo(11);
    actual.forEach((name, role) -> {
      assertThat(role.path("description").asText()).as("mô tả của " + name)
          .startsWith(expected.get(name) + " ");
      assertThat(role.path("composite").asBoolean(false)).as(name + " không là composite").isFalse();
      assertThat(role.has("composites")).as(name + " không có composites").isFalse();
    });
  }

  @Test
  void cauHinhRealmTheoAdr08VaBr0005() {
    JsonNode r = realm();
    assertThat(r.path("organizationsEnabled").asBoolean()).isTrue();
    assertThat(r.path("registrationAllowed").asBoolean()).isFalse();
    assertThat(r.path("defaultSignatureAlgorithm").asText()).isEqualTo("ES256");
    assertThat(r.path("revokeRefreshToken").asBoolean()).isTrue();
    assertThat(r.path("refreshTokenMaxReuse").asInt()).isZero();
    assertThat(r.path("bruteForceProtected").asBoolean()).isTrue();
    assertThat(r.path("failureFactor").asInt()).isEqualTo(5);
    assertThat(r.path("waitIncrementSeconds").asInt()).isEqualTo(900);
    assertThat(r.path("maxFailureWaitSeconds").asInt()).isEqualTo(900);
    assertThat(r.path("permanentLockout").asBoolean()).isTrue();
    // Khoá cố định ở lần sai thứ 20 (TL-04 SEC-14): failureFactor + maxTemporaryLockouts = 20
    assertThat(r.path("failureFactor").asInt() + r.path("maxTemporaryLockouts").asInt()).isEqualTo(20);
    assertThat(r.path("maxDeltaTimeSeconds").asInt()).isEqualTo(86400);
    assertThat(r.path("accessTokenLifespan").asInt()).isEqualTo(300);
    assertThat(r.path("ssoSessionIdleTimeout").asInt()).isEqualTo(1800);
    assertThat(r.path("ssoSessionMaxLifespan").asInt()).isEqualTo(36000);
    assertThat(r.path("clientSessionMaxLifespan").asInt()).isEqualTo(28800);
    assertThat(r.path("passwordPolicy").asText()).contains("hashAlgorithm(argon2)", "length(12)", "passwordHistory(");
    assertThat(r.path("sslRequired").asText()).isEqualTo("all");
  }

  @Test
  void clientCongKhaiChiDungCodeVaPkceS256() {
    JsonNode r = realm();
    Map<String, JsonNode> clients = new LinkedHashMap<>();
    r.path("clients").forEach(c -> clients.put(c.path("clientId").asText(), c));
    assertThat(clients.keySet())
        .containsExactlyInAnyOrder("mission-web", "apisix-gateway", "mission-iam-broker", "org-service");
    for (String id : new String[] {"mission-web"}) {
      JsonNode c = clients.get(id);
      assertThat(c.path("publicClient").asBoolean()).as(id).isTrue();
      assertThat(c.path("standardFlowEnabled").asBoolean()).as(id).isTrue();
      assertThat(c.path("implicitFlowEnabled").asBoolean()).as(id).isFalse();
      assertThat(c.path("directAccessGrantsEnabled").asBoolean()).as(id).isFalse();
      assertThat(c.path("attributes").path("pkce.code.challenge.method").asText()).as(id).isEqualTo("S256");
      assertThat(c.path("redirectUris").isArray() && !c.path("redirectUris").isEmpty()).as(id).isTrue();
      c.path("redirectUris").forEach(u -> assertThat(u.asText()).as(id + " redirect URI").isNotEqualTo("*"));
    }
    JsonNode gw = clients.get("apisix-gateway");
    assertThat(gw.path("publicClient").asBoolean()).isFalse();
    assertThat(gw.path("standardFlowEnabled").asBoolean()).isFalse();
    assertThat(gw.path("implicitFlowEnabled").asBoolean()).isFalse();
    assertThat(gw.path("directAccessGrantsEnabled").asBoolean()).isFalse();
    assertThat(gw.path("serviceAccountsEnabled").asBoolean()).isFalse();
    // DV-02-04: tài khoản dịch vụ gọi org-service chỉ dùng client credentials.
    JsonNode broker = clients.get("mission-iam-broker");
    assertThat(broker.path("publicClient").asBoolean()).isFalse();
    assertThat(broker.path("serviceAccountsEnabled").asBoolean()).isTrue();
    assertThat(broker.path("standardFlowEnabled").asBoolean()).isFalse();
    assertThat(broker.path("directAccessGrantsEnabled").asBoolean()).isFalse();
    // DV-04-07: org-service chỉ dùng client credentials, tài khoản dịch vụ chỉ xem và sửa người dùng.
    JsonNode org = clients.get("org-service");
    assertThat(org.path("publicClient").asBoolean()).isFalse();
    assertThat(org.path("serviceAccountsEnabled").asBoolean()).isTrue();
    assertThat(org.path("standardFlowEnabled").asBoolean()).isFalse();
    assertThat(org.path("directAccessGrantsEnabled").asBoolean()).isFalse();
    JsonNode sa = null;
    for (JsonNode u : r.path("users")) {
      if ("org-service".equals(u.path("serviceAccountClientId").asText())) {
        sa = u;
      }
    }
    assertThat(sa).isNotNull();
    assertThat(sa.path("realmRoles").isMissingNode() || sa.path("realmRoles").isEmpty()).isTrue();
    assertThat(RepoFiles.JSON.convertValue(sa.path("clientRoles").path("realm-management"), String[].class))
        .containsExactlyInAnyOrder("view-users", "manage-users");
    assertThat(sa.path("clientRoles").size()).isEqualTo(1);
  }

  @Test
  void vneidQuaMoiGioiCoPkceNonceVaKhongLuuToken() {
    JsonNode idps = realm().path("identityProviders");
    assertThat(idps).hasSize(1);
    JsonNode v = idps.get(0);
    assertThat(v.path("alias").asText()).isEqualTo("vneid");
    assertThat(v.path("providerId").asText()).isEqualTo("mission-vneid");
    assertThat(v.path("storeToken").asBoolean()).isFalse();
    assertThat(v.path("trustEmail").asBoolean()).isFalse();
    assertThat(v.path("firstBrokerLoginFlowAlias").asText()).isEqualTo("mission-vneid-first-login");
    assertThat(v.path("postBrokerLoginFlowAlias").asText()).isEqualTo("mission-vneid-post-login");
    JsonNode c = v.path("config");
    assertThat(c.path("pkceEnabled").asText()).isEqualTo("true");
    assertThat(c.path("pkceMethod").asText()).isEqualTo("S256");
    assertThat(c.path("disableNonce").asText()).isEqualTo("false");
    assertThat(c.path("validateSignature").asText()).isEqualTo("true");
    assertThat(c.path("missionRequiredLevel").asText()).isEqualTo("2");
    assertThat(c.path("missionTimeoutMillis").asText()).isEqualTo("10000");
    // Luồng đăng nhập lần đầu chỉ có bước liên kết, không có bước tạo tài khoản; sau đó kiểm trạng thái tenant.
    realm().path("authenticationFlows").forEach(f -> {
      if (f.path("alias").asText().startsWith("mission-vneid")) {
        assertThat(f.path("authenticationExecutions").findValuesAsText("authenticator"))
            .containsExactly("mission-vneid-link", "mission-tenant-status");
      }
    });
  }

  @Test
  void luongDangNhapKiemTrangThaiTenantSauKhiXacDinhNguoiDung() {
    // TL-04 mục 11.4: tenant TAM_NGUNG chặn đăng nhập ở Keycloak, kể cả khi còn phiên SSO (cookie).
    JsonNode browser = null;
    JsonNode identify = null;
    for (JsonNode f : realm().path("authenticationFlows")) {
      if ("mission-browser".equals(f.path("alias").asText())) {
        browser = f;
      } else if ("mission-identify".equals(f.path("alias").asText())) {
        identify = f;
      }
    }
    assertThat(browser).isNotNull();
    assertThat(identify).isNotNull();
    JsonNode steps = browser.path("authenticationExecutions");
    assertThat(steps).hasSize(2);
    assertThat(steps.get(0).path("flowAlias").asText()).isEqualTo("mission-identify");
    assertThat(steps.get(0).path("requirement").asText()).isEqualTo("REQUIRED");
    assertThat(steps.get(1).path("authenticator").asText()).isEqualTo("mission-tenant-status");
    assertThat(steps.get(1).path("requirement").asText()).isEqualTo("REQUIRED");
    assertThat(identify.path("authenticationExecutions").findValuesAsText("authenticator"))
        .contains("auth-cookie", "identity-provider-redirector");
    identify.path("authenticationExecutions").forEach(e ->
        assertThat(e.path("requirement").asText()).isEqualTo("ALTERNATIVE"));
  }

  @Test
  void khongCoBiMatTrongMau() {
    JsonNode raw = RepoFiles.readYaml(TEMPLATE);
    raw.path("clients").forEach(c -> {
      if (c.has("secret")) {
        assertThat(c.path("secret").asText()).as("secret của " + c.path("clientId").asText())
            .matches("\\$\\(env:[A-Z0-9_]+\\)");
      }
    });
    // Chỉ có người dùng của tài khoản dịch vụ (DV-02-04), không có tài khoản người hay mật khẩu.
    raw.path("users").forEach(u -> {
      assertThat(u.path("username").asText()).as("chỉ tài khoản dịch vụ").startsWith("service-account-");
      assertThat(u.has("serviceAccountClientId")).isTrue();
      assertThat(u.has("credentials")).as("không có mật khẩu").isFalse();
    });
    assertThat(TEMPLATE).doesNotContainPattern("(?i)-----BEGIN [A-Z ]*PRIVATE KEY-----");
    raw.path("components").path("org.keycloak.keys.KeyProvider").forEach(k ->
        assertThat(k.path("config").has("privateKey")).as("khoá ký không nằm trong mẫu").isFalse());
  }

  @Test
  void moiBienCuaMauDeuDuocJobHelmTruyenVao() {
    Set<String> used = RepoFiles.templateVariables(TEMPLATE);
    // Mọi chỗ mở biến phải đúng cú pháp, kể cả trong chú thích (keycloak-config-cli thay cả chú thích)
    assertThat(TEMPLATE.split("\\$\\(", -1).length - 1).as("số chỗ mở biến")
        .isEqualTo((int) Pattern.compile("\\$\\(env:[A-Z0-9_]+\\)").matcher(TEMPLATE).results().count());
    String job = RepoFiles.read(RepoFiles.chart().resolve("templates/job-realm-import.yaml"));
    Set<String> provided = new TreeSet<>();
    Matcher m = Pattern.compile("- name: (MISSION_[A-Z0-9_]+)").matcher(job);
    while (m.find()) {
      provided.add(m.group(1));
    }
    assertThat(provided).as("biến Job Helm truyền cho keycloak-config-cli").isEqualTo(used);
    assertThat(job).contains("IMPORT_VARSUBSTITUTION_ENABLED", "secretKeyRef");
  }
}
