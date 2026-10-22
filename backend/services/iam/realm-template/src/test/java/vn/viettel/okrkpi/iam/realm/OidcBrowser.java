package vn.viettel.okrkpi.iam.realm;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieHandler;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Giả lập trình duyệt cho luồng Authorization Code + PKCE (QT-00, SCR-0001): mở trang đăng nhập Keycloak,
 * gửi biểu mẫu, bắt `code` ở redirect, đổi token. Mỗi phiên trình duyệt một kho cookie riêng.
 */
final class OidcBrowser {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Pattern FORM = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*>", Pattern.DOTALL);
  /** Thông báo lỗi dưới ô nhập của theme keycloak.v2 (khác khung chứa rỗng input-error-container-*). */
  private static final Pattern STEP_ERROR = Pattern.compile("id=\"input-error-(?!container)");
  private static final Pattern ACTION = Pattern.compile("action=\"([^\"]+)\"");

  final String baseUrl;
  final String realm;
  final String clientId;
  final String redirectUri;
  final HttpClient http;

  final String state = randomUrlSafe(16);
  final String nonce = randomUrlSafe(16);
  final String codeVerifier = randomUrlSafe(32);

  /** Trang HTML gần nhất (trang đăng nhập hoặc trang báo lỗi). */
  String lastPage;

  OidcBrowser(String baseUrl, String realm, String clientId, String redirectUri) {
    this.baseUrl = baseUrl;
    this.realm = realm;
    this.clientId = clientId;
    this.redirectUri = redirectUri;
    this.http = HttpClient.newBuilder()
        .cookieHandler(new LocalhostCookieJar())
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  static String randomUrlSafe(int bytes) {
    byte[] b = new byte[bytes];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  static String s256(String verifier) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  String endpoint(String name) {
    return baseUrl + "/realms/" + realm + "/protocol/openid-connect/" + name;
  }

  /** Tham số yêu cầu xác thực mặc định: code + PKCE S256, state, nonce, giao diện tiếng Việt. */
  Map<String, String> defaultAuthParams() {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("client_id", clientId);
    p.put("response_type", "code");
    p.put("scope", "openid");
    p.put("redirect_uri", redirectUri);
    p.put("state", state);
    p.put("nonce", nonce);
    p.put("code_challenge", s256(codeVerifier));
    p.put("code_challenge_method", "S256");
    p.put("ui_locales", "vi");
    return p;
  }

  HttpResponse<String> authorize(Map<String, String> params) {
    String url = endpoint("auth") + "?" + form(params);
    return send(HttpRequest.newBuilder(URI.create(url)).header("Accept-Language", "vi").GET().build());
  }

  /** Mở trang đăng nhập; trả về true nếu nhận được biểu mẫu đăng nhập. */
  boolean openLoginPage() {
    HttpResponse<String> r = authorize(defaultAuthParams());
    lastPage = r.body();
    return r.statusCode() == 200 && FORM.matcher(lastPage).find();
  }

  /**
   * Gửi biểu mẫu đăng nhập trên trang hiện tại. Trả về `code` nếu Keycloak chuyển hướng về ứng dụng,
   * rỗng nếu Keycloak trả lại trang đăng nhập (sai mật khẩu, tài khoản bị khoá...).
   */
  Optional<String> submitLogin(String username, String password) {
    Optional<String> code = postLoginForm(username, password);
    // Khi realm đã có Organization, Keycloak 26 chuyển sang đăng nhập hai bước (Organization identity-first):
    // bước 1 nhận tên đăng nhập, bước 2 trang chỉ có ô mật khẩu. Trang bước 2 không mang thông báo lỗi.
    if (code.isEmpty() && lastStatus == 200 && !lastPage.contains("name=\"username\"")
        && lastPage.contains("name=\"password\"") && lastPage.contains("kc-attempted-username")
        && !STEP_ERROR.matcher(lastPage).find()) {
      identityFirstSteps++;
      code = postLoginForm(username, password);
    }
    return code;
  }

  /** Số lần gặp bước nhập mật khẩu riêng của đăng nhập hai bước. */
  int identityFirstSteps;

  private Optional<String> postLoginForm(String username, String password) {
    Matcher f = FORM.matcher(lastPage);
    if (!f.find()) {
      throw new IllegalStateException("Không thấy biểu mẫu đăng nhập trên trang hiện tại");
    }
    Matcher a = ACTION.matcher(f.group());
    if (!a.find()) {
      throw new IllegalStateException("Biểu mẫu đăng nhập không có action");
    }
    String action = a.group(1).replace("&amp;", "&");
    HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(action))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept-Language", "vi")
        .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
            "username", username, "password", password, "credentialId", ""))))
        .build());
    if (r.statusCode() == 302) {
      String loc = r.headers().firstValue("Location").orElseThrow();
      Map<String, String> q = query(URI.create(loc));
      if (!loc.startsWith(redirectUri)) {
        throw new IllegalStateException("Chuyển hướng ngoài redirect URI: " + loc);
      }
      if (!state.equals(q.get("state"))) {
        throw new IllegalStateException("state trả về không khớp");
      }
      return Optional.ofNullable(q.get("code"));
    }
    lastPage = r.body();
    lastStatus = r.statusCode();
    return Optional.empty();
  }

  /** Mã HTTP của lần gửi biểu mẫu gần nhất không chuyển hướng về ứng dụng. */
  int lastStatus;

  /** Tóm tắt trang gần nhất (tiêu đề và thông báo lỗi) để ghi vào thông điệp kiểm thử. */
  String pageSummary() {
    if (lastPage == null) {
      return "";
    }
    try {
      java.nio.file.Path dir = java.nio.file.Path.of("target", "trang-loi");
      java.nio.file.Files.createDirectories(dir);
      java.nio.file.Files.writeString(dir.resolve("trang-" + System.nanoTime() + ".html"), lastPage);
    } catch (IOException e) {
      // chỉ phục vụ chẩn đoán
    }
    String t = lastPage.replaceAll("(?s)<script.*?</script>", " ").replaceAll("(?s)<style.*?</style>", " ")
        .replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    return "HTTP " + lastStatus + ": " + (t.length() > 600 ? t.substring(0, 600) : t);
  }

  /** Đổi code lấy token; codeVerifier null để thử thiếu code_verifier. */
  HttpResponse<String> exchange(String code, String verifier) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("grant_type", "authorization_code");
    p.put("client_id", clientId);
    p.put("code", code);
    p.put("redirect_uri", redirectUri);
    if (verifier != null) {
      p.put("code_verifier", verifier);
    }
    return postForm(endpoint("token"), p, null);
  }

  HttpResponse<String> refresh(String refreshToken) {
    return postForm(endpoint("token"), Map.of(
        "grant_type", "refresh_token", "client_id", clientId, "refresh_token", refreshToken), null);
  }

  HttpResponse<String> postForm(String url, Map<String, String> params, String basicAuth) {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form(params)));
    if (basicAuth != null) {
      b.header("Authorization", "Basic " + Base64.getEncoder()
          .encodeToString(basicAuth.getBytes(StandardCharsets.UTF_8)));
    }
    return send(b.build());
  }

  HttpResponse<String> send(HttpRequest req) {
    try {
      return http.send(req, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  static String form(Map<String, String> params) {
    return params.entrySet().stream()
        .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }

  static Map<String, String> query(URI uri) {
    Map<String, String> out = new LinkedHashMap<>();
    String q = uri.getRawQuery();
    if (q == null) {
      q = uri.getRawFragment();
    }
    if (q == null) {
      return out;
    }
    for (String kv : q.split("&")) {
      int i = kv.indexOf('=');
      String k = i < 0 ? kv : kv.substring(0, i);
      String v = i < 0 ? "" : kv.substring(i + 1);
      out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
    }
    return out;
  }

  /** Phần header và payload của JWT. */
  static JsonNode[] decodeJwt(String jwt) {
    String[] parts = jwt.split("\\.");
    Base64.Decoder d = Base64.getUrlDecoder();
    return new JsonNode[] {
      RepoFiles.readYaml(new String(d.decode(parts[0]), StandardCharsets.UTF_8)),
      RepoFiles.readYaml(new String(d.decode(parts[1]), StandardCharsets.UTF_8))
    };
  }

  /**
   * Kho cookie tối giản của trình duyệt giả lập. Keycloak 26 đặt cookie phiên đăng nhập với cờ Secure;
   * trình duyệt coi http://localhost là ngữ cảnh an toàn nên vẫn gửi cookie đó, còn CookieManager của JDK
   * thì không, vì vậy kho này bỏ qua cờ Secure (chỉ dùng cho kiểm thử với Keycloak trong container).
   */
  static final class LocalhostCookieJar extends CookieHandler {
    private final Map<String, String> cookies = new LinkedHashMap<>();

    @Override
    public synchronized Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
      if (cookies.isEmpty()) {
        return Map.of();
      }
      String v = cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
          .collect(Collectors.joining("; "));
      return Map.of("Cookie", List.of(v));
    }

    @Override
    public synchronized void put(URI uri, Map<String, List<String>> headers) {
      headers.forEach((k, list) -> {
        if (k != null && k.equalsIgnoreCase("Set-Cookie")) {
          for (String sc : list) {
            String first = sc.split(";", 2)[0];
            int i = first.indexOf('=');
            if (i <= 0) {
              continue;
            }
            String name = first.substring(0, i).trim();
            String value = first.substring(i + 1).trim();
            String lower = sc.toLowerCase(java.util.Locale.ROOT);
            if (value.isEmpty() || lower.contains("max-age=0") || lower.contains("expires=thu, 01 jan 1970")) {
              cookies.remove(name);
            } else {
              cookies.put(name, value);
            }
          }
        }
      });
    }
  }
}
