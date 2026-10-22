package vn.viettel.okrkpi.iam.realm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Đường dẫn tới mẫu realm, chart iam và ma trận phân quyền trong repo (DV-02-01). */
final class RepoFiles {

  static final ObjectMapper JSON = new ObjectMapper();
  static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  /** Redirect URI của client web dùng trong kiểm thử (không có máy chủ thật nghe ở đây). */
  static final String WEB_REDIRECT = "http://localhost:4200/callback";

  private RepoFiles() {}

  static Path root() {
    String p = System.getProperty("mission.repo.root");
    Path root = p != null ? Path.of(p) : Path.of("../../..");
    return root.toAbsolutePath().normalize();
  }

  static Path chart() {
    return root().resolve("deploy/charts/iam");
  }

  static Path realmTemplate() {
    return chart().resolve("files/realm/realm-cell.yaml");
  }

  static Path matrix() {
    return root().resolve("docs/phan-quyen/ma-tran.json");
  }

  static String read(Path p) {
    try {
      return Files.readString(p);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static JsonNode readJson(Path p) {
    try {
      return JSON.readTree(p.toFile());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static JsonNode readYaml(String text) {
    try {
      return YAML.readTree(text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Giá trị mặc định của chart (values.yaml). */
  static JsonNode chartValues() {
    return readYaml(read(chart().resolve("values.yaml")));
  }

  private static final Pattern VAR = Pattern.compile("\\$\\(env:([A-Z0-9_]+)\\)");

  /**
   * Thay biến $(env:X) giống keycloak-config-cli (undefined-is-error), để đọc mẫu realm sau khi thay.
   */
  static String substitute(String template, Map<String, String> env) {
    Matcher m = VAR.matcher(template);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String v = env.get(m.group(1));
      if (v == null) {
        throw new IllegalArgumentException("Thiếu biến " + m.group(1));
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(v));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  static java.util.Set<String> templateVariables(String template) {
    java.util.Set<String> out = new java.util.TreeSet<>();
    Matcher m = VAR.matcher(template);
    while (m.find()) {
      out.add(m.group(1));
    }
    return out;
  }

  /**
   * Biến cho mẫu realm lấy từ values.yaml của chart, như Job Helm truyền vào; cho phép ghi đè.
   * Bí mật và URI là giá trị kiểm thử.
   */
  static Map<String, String> envFromChartValues(String realmName, String apisixSecret, Map<String, String> overrides) {
    JsonNode r = chartValues().path("realm");
    JsonNode t = r.path("tokens");
    JsonNode bf = r.path("bruteForce");
    Map<String, String> env = new LinkedHashMap<>();
    env.put("MISSION_REALM", realmName);
    env.put("MISSION_REALM_DISPLAY_NAME", r.path("displayName").asText());
    env.put("MISSION_CELL", r.path("cell").asText());
    env.put("MISSION_SSL_REQUIRED", r.path("sslRequired").asText());
    env.put("MISSION_WEB_REDIRECT_URIS", "[\"" + WEB_REDIRECT + "\"]");
    env.put("MISSION_WEB_ORIGINS", "[\"http://localhost:4200\"]");
    env.put("MISSION_WEB_POST_LOGOUT_URIS", "http://localhost:4200/*");
    env.put("MISSION_MOBILE_REDIRECT_URIS", r.path("mobile").path("redirectUris").toString());
    env.put("MISSION_MOBILE_POST_LOGOUT_URIS", String.join("##",
        JSON.convertValue(r.path("mobile").path("postLogoutRedirectUris"), String[].class)));
    env.put("MISSION_ACCESS_TOKEN_LIFESPAN", t.path("accessTokenLifespan").asText());
    env.put("MISSION_SSO_IDLE", t.path("ssoSessionIdleTimeout").asText());
    env.put("MISSION_SSO_MAX", t.path("ssoSessionMaxLifespan").asText());
    env.put("MISSION_CLIENT_SESSION_IDLE", t.path("clientSessionIdleTimeout").asText());
    env.put("MISSION_CLIENT_SESSION_MAX", t.path("clientSessionMaxLifespan").asText());
    env.put("MISSION_BF_FAILURE_FACTOR", bf.path("failureFactor").asText());
    env.put("MISSION_BF_WAIT", bf.path("waitSeconds").asText());
    env.put("MISSION_BF_MAX_DELTA", bf.path("maxDeltaTimeSeconds").asText());
    env.put("MISSION_BF_MAX_TEMP_LOCKOUTS", bf.path("maxTemporaryLockouts").asText());
    JsonNode mfa = r.path("mfa");
    env.put("MISSION_AAL2_MAX_AGE", mfa.path("aal2MaxAge").asText());
    env.put("MISSION_MFA_ROLES", String.join(",", JSON.convertValue(mfa.path("roles"), String[].class)));
    env.put("MISSION_MFA_WEIGHT_NEW_DEVICE", mfa.path("risk").path("weightNewDevice").asText());
    env.put("MISSION_MFA_WEIGHT_NEW_NETWORK", mfa.path("risk").path("weightNewNetwork").asText());
    env.put("MISSION_MFA_WEIGHT_FAILURES", mfa.path("risk").path("weightFailures").asText());
    env.put("MISSION_MFA_RISK_THRESHOLD", mfa.path("risk").path("threshold").asText());
    env.put("MISSION_SIGNING_KEY_PRIORITY", r.path("signingKeyPriority").asText());
    env.put("MISSION_APISIX_CLIENT_SECRET", apisixSecret);
    env.putAll(overrides);
    return env;
  }
}
