package vn.viettel.okrkpi.iam.realm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IdP OIDC giả lập theo mô hình VNeID (DV-02-04) và org-service giả, chạy trong JVM kiểm thử: Keycloak trong
 * container gọi tới qua {@code host.testcontainers.internal}. Điểm cuối {@code /vneid/authorize}, {@code /vneid/token}
 * (kiểm PKCE S256, client_secret_post), {@code /vneid/jwks}; id_token ký RS256 mang claim mức tài khoản và số định
 * danh. {@code /org/internal/v1/org/tenants/{t}/persons/national-id-match} chỉ trả lời token có vai trò
 * {@code svc_iam}. {@code /registry/api/v1/registry/resolve?tenant=} là tenant-registry giả (SVC-03): trả trạng thái
 * tenant trong {@link #tenantStatus}, mặc định {@code DANG_VAN_HANH}; giá trị {@code LOI_REGISTRY} giả lập
 * registry gián đoạn (503). Mọi dữ liệu là giả lập.
 */
final class VneidGiaLap implements AutoCloseable {

  /** Tài khoản VNeID của lần đăng nhập kế tiếp. */
  record Account(String sub, String level, String nationalId) {
  }

  static final String CLIENT_ID = "mission";
  static final String HOST_ALIAS = "host.testcontainers.internal";

  private final HttpServer server;
  private final KeyPair keys;
  private final String kid = "kiem-thu-" + UUID.randomUUID();
  final String clientSecret = UUID.randomUUID().toString();

  volatile Account next;
  /** Thay nonce trong id_token (giả mạo); null là trả đúng nonce đã nhận. */
  volatile String nonceOverride;
  /** Trì hoãn điểm cuối token (giả lập VNeID quá hạn). */
  volatile long tokenDelayMillis;

  final AtomicInteger authorizeCalls = new AtomicInteger();
  final List<String> challengeMethods = new CopyOnWriteArrayList<>();
  final AtomicInteger pkceVerified = new AtomicInteger();
  final AtomicInteger pkceRejected = new AtomicInteger();
  final List<String> orgCalls = new CopyOnWriteArrayList<>();

  /** Tenant → trạng thái vòng đời (tenant-registry giả); tenant không có trong bảng đang vận hành. */
  final Map<String, String> tenantStatus = new ConcurrentHashMap<>();
  final List<String> registryCalls = new CopyOnWriteArrayList<>();

  /** Số định danh → mã người của tenant (org-service giả). */
  final Map<String, String> persons = new ConcurrentHashMap<>();

  private record Pending(Account account, String nonce, String challenge, String redirectUri) {
  }

  private final Map<String, Pending> codes = new ConcurrentHashMap<>();

  VneidGiaLap() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      keys = g.generateKeyPair();
      server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/vneid/authorize", this::authorize);
    server.createContext("/vneid/token", this::token);
    server.createContext("/vneid/jwks", this::jwks);
    server.createContext("/org/", this::org);
    server.createContext("/registry/", this::registry);
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  /** Địa chỉ mà Keycloak trong container dùng. */
  String issuer() {
    return "http://" + HOST_ALIAS + ":" + port() + "/vneid";
  }

  String orgUrl() {
    return "http://" + HOST_ALIAS + ":" + port() + "/org";
  }

  String registryUrl() {
    return "http://" + HOST_ALIAS + ":" + port() + "/registry";
  }

  private void registry(HttpExchange ex) throws IOException {
    String auth = ex.getRequestHeaders().getFirst("Authorization");
    Map<String, String> q = OidcBrowser.query(ex.getRequestURI());
    if (auth == null || !auth.startsWith("Bearer ") || !"GET".equals(ex.getRequestMethod())
        || !ex.getRequestURI().getPath().equals("/registry/api/v1/registry/resolve") || q.get("tenant") == null) {
      reply(ex, 401, "application/problem+json", "{}");
      return;
    }
    String tenant = q.get("tenant");
    registryCalls.add(tenant);
    if ("LOI_REGISTRY".equals(tenantStatus.get(tenant))) {
      reply(ex, 503, "application/problem+json", "{}");
      return;
    }
    ObjectNode out = RepoFiles.JSON.createObjectNode();
    out.put("tenantId", tenant).put("tenantCode", tenant).put("tenancyModel", "POOL")
        .put("status", tenantStatus.getOrDefault(tenant, "DANG_VAN_HANH")).put("cacheTtlSeconds", 300);
    reply(ex, 200, "application/json", out.toString());
  }

  private void authorize(HttpExchange ex) throws IOException {
    authorizeCalls.incrementAndGet();
    Map<String, String> q = OidcBrowser.query(ex.getRequestURI());
    challengeMethods.add(String.valueOf(q.get("code_challenge_method")));
    Account a = next;
    if (a == null || !CLIENT_ID.equals(q.get("client_id"))) {
      reply(ex, 400, "text/plain", "khong co tai khoan gia lap");
      return;
    }
    String code = UUID.randomUUID().toString();
    codes.put(code, new Pending(a, q.get("nonce"), q.get("code_challenge"), q.get("redirect_uri")));
    String loc = q.get("redirect_uri") + "?code=" + code + "&state="
        + java.net.URLEncoder.encode(q.get("state"), StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Location", loc);
    reply(ex, 302, "text/plain", "");
  }

  private void token(HttpExchange ex) throws IOException {
    long delay = tokenDelayMillis;
    if (delay > 0) {
      try {
        Thread.sleep(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    Map<String, String> f = OidcBrowser.query(URI.create("x:/?" + new String(ex.getRequestBody().readAllBytes(),
        StandardCharsets.UTF_8)));
    Pending p = codes.remove(String.valueOf(f.get("code")));
    if (p == null || !CLIENT_ID.equals(f.get("client_id")) || !clientSecret.equals(f.get("client_secret"))
        || !p.redirectUri().equals(f.get("redirect_uri"))) {
      reply(ex, 400, "application/json", "{\"error\":\"invalid_grant\"}");
      return;
    }
    String verifier = f.get("code_verifier");
    if (verifier == null || p.challenge() == null || !OidcBrowser.s256(verifier).equals(p.challenge())) {
      pkceRejected.incrementAndGet();
      reply(ex, 400, "application/json", "{\"error\":\"invalid_grant\",\"error_description\":\"PKCE\"}");
      return;
    }
    pkceVerified.incrementAndGet();
    long now = System.currentTimeMillis() / 1000;
    ObjectNode claims = RepoFiles.JSON.createObjectNode();
    claims.put("iss", issuer());
    claims.put("sub", p.account().sub());
    claims.put("aud", CLIENT_ID);
    claims.put("iat", now);
    claims.put("exp", now + 300);
    claims.put("nonce", nonceOverride != null ? nonceOverride : p.nonce());
    claims.put("name", "Công dân giả lập");
    if (p.account().level() != null) {
      claims.put("muc_dinh_danh", p.account().level());
    }
    if (p.account().nationalId() != null) {
      claims.put("so_dinh_danh", p.account().nationalId());
    }
    ObjectNode res = RepoFiles.JSON.createObjectNode();
    res.put("access_token", UUID.randomUUID().toString());
    res.put("token_type", "Bearer");
    res.put("expires_in", 300);
    res.put("id_token", sign(claims));
    reply(ex, 200, "application/json", res.toString());
  }

  private void jwks(HttpExchange ex) throws IOException {
    RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
    ObjectNode k = RepoFiles.JSON.createObjectNode();
    k.put("kty", "RSA").put("kid", kid).put("use", "sig").put("alg", "RS256")
        .put("n", b64(unsigned(pub.getModulus()))).put("e", b64(unsigned(pub.getPublicExponent())));
    ObjectNode out = RepoFiles.JSON.createObjectNode();
    out.putArray("keys").add(k);
    reply(ex, 200, "application/json", out.toString());
  }

  private void org(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    String auth = ex.getRequestHeaders().getFirst("Authorization");
    boolean role = false;
    if (auth != null && auth.startsWith("Bearer ")) {
      JsonNode payload = OidcBrowser.decodeJwt(auth.substring(7))[1];
      for (JsonNode r : payload.path("realm_access").path("roles")) {
        role |= "svc_iam".equals(r.asText());
      }
    }
    if (!role || !"POST".equals(ex.getRequestMethod()) || !path.endsWith("/persons/national-id-match")) {
      reply(ex, 403, "application/json", "{}");
      return;
    }
    String tenant = path.replaceAll(".*/tenants/([^/]+)/persons/.*", "$1");
    JsonNode body = RepoFiles.readYaml(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    String person = persons.get(tenant + "|" + body.path("nationalId").asText());
    orgCalls.add(tenant);
    reply(ex, 200, "application/json", person == null ? "{\"matches\":0}"
        : "{\"matches\":1,\"personId\":\"" + person + "\"}");
  }

  private String sign(ObjectNode claims) {
    try {
      String header = b64(("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}")
          .getBytes(StandardCharsets.UTF_8));
      String body = b64(claims.toString().getBytes(StandardCharsets.UTF_8));
      Signature s = Signature.getInstance("SHA256withRSA");
      s.initSign(keys.getPrivate());
      s.update((header + "." + body).getBytes(StandardCharsets.US_ASCII));
      return header + "." + body + "." + b64(s.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] unsigned(BigInteger v) {
    byte[] b = v.toByteArray();
    return b[0] == 0 ? java.util.Arrays.copyOfRange(b, 1, b.length) : b;
  }

  private static String b64(byte[] b) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static void reply(HttpExchange ex, int status, String type, String body) {
    try (ex) {
      byte[] b = body.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", type);
      ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
      if (b.length > 0) {
        ex.getResponseBody().write(b);
      }
    } catch (IOException e) {
      // Keycloak đã ngắt kết nối khi quá hạn: bỏ qua.
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
