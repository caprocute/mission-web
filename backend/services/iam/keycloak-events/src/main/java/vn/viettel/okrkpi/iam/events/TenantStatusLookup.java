package vn.viettel.okrkpi.iam.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import org.jboss.logging.Logger;

/**
 * Tra trạng thái vòng đời của tenant ở tenant-registry (SVC-03, {@code GET /api/v1/registry/resolve?tenant=}) cho bước
 * {@link MissionTenantStatusAuthenticator}. Registry là dịch vụ toàn cục, chỉ nhận token của realm nền tảng mang vai
 * trò {@code service_client} (như client {@code apisix-global} của APISIX), nên Keycloak của cell gọi bằng một client
 * credentials riêng; lưu đệm theo tenant {@value #CACHE_SECONDS} giây để đăng nhập không phụ thuộc từng lần vào
 * registry.
 *
 * <p>Biến môi trường của pod Keycloak:
 *
 * <ul>
 *   <li>{@code MISSION_TENANT_REGISTRY_URL}: địa chỉ nội bộ của tenant-registry; để trống thì không kiểm (dev);
 *   <li>{@code MISSION_TENANT_REGISTRY_TOKEN_URL}, {@code MISSION_TENANT_REGISTRY_CLIENT_ID}, {@code
 *       MISSION_TENANT_REGISTRY_CLIENT_SECRET}: client credentials của realm nền tảng (vai trò {@code
 *       service_client});
 *   <li>{@code MISSION_TENANT_REGISTRY_TIMEOUT_MS}: thời hạn mỗi lời gọi (mặc định 3000).
 * </ul>
 *
 * Registry lỗi hoặc không trả lời: dùng kết quả đệm cũ nếu có, không có thì trả rỗng (không chặn đăng nhập). Lý do: tầng
 * dịch vụ vẫn chặn lệnh ghi của tenant tạm ngừng (TL-04 mục 11.4), nên một lần registry gián đoạn không được làm mọi
 * người dùng mất đăng nhập.
 */
final class TenantStatusLookup {

  static final int CACHE_SECONDS = 30;
  static final String PATH = "/api/v1/registry/resolve?tenant=";
  private static final Logger LOG = Logger.getLogger(TenantStatusLookup.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private record Cached(String status, long at) {}

  private final String registryUrl;
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final Duration timeout;
  private final LongSupplier clock;
  private final HttpClient http;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();
  private String token;
  private long tokenExpiresAt;

  TenantStatusLookup(UnaryOperator<String> env, LongSupplier clock) {
    this.registryUrl = trim(env.apply("MISSION_TENANT_REGISTRY_URL")).replaceAll("/+$", "");
    this.tokenUrl = trim(env.apply("MISSION_TENANT_REGISTRY_TOKEN_URL"));
    String id = trim(env.apply("MISSION_TENANT_REGISTRY_CLIENT_ID"));
    this.clientId = id.isEmpty() ? "keycloak-tenant-status" : id;
    this.clientSecret = trim(env.apply("MISSION_TENANT_REGISTRY_CLIENT_SECRET"));
    String t = trim(env.apply("MISSION_TENANT_REGISTRY_TIMEOUT_MS"));
    this.timeout = Duration.ofMillis(t.isEmpty() ? 3000 : Long.parseLong(t));
    this.clock = clock;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  static TenantStatusLookup fromEnv() {
    return new TenantStatusLookup(System::getenv, System::currentTimeMillis);
  }

  boolean enabled() {
    return !registryUrl.isEmpty();
  }

  /** Trạng thái tenant ({@code DANG_VAN_HANH}, {@code TAM_NGUNG}...), rỗng khi không tra được. */
  Optional<String> status(String tenant) {
    if (!enabled() || tenant == null || tenant.isBlank()) {
      return Optional.empty();
    }
    long now = clock.getAsLong();
    Cached hit = cache.get(tenant);
    if (hit != null && now - hit.at() < CACHE_SECONDS * 1000L) {
      return Optional.of(hit.status());
    }
    try {
      HttpRequest req = HttpRequest.newBuilder(URI.create(
              registryUrl + PATH + URLEncoder.encode(tenant, StandardCharsets.UTF_8)))
          .timeout(timeout)
          .header("Accept", "application/json")
          .header("Authorization", "Bearer " + token())
          .GET()
          .build();
      String status = JSON.readTree(send(req, "tenant-registry")).path("status").asText("");
      if (status.isEmpty()) {
        throw new IOException("tenant-registry trả kết quả không có trạng thái");
      }
      cache.put(tenant, new Cached(status, now));
      return Optional.of(status);
    } catch (IOException | RuntimeException e) {
      LOG.warnf("Không tra được trạng thái tenant %s: %s", tenant, e.getMessage());
      return hit == null ? Optional.empty() : Optional.of(hit.status());
    }
  }

  private synchronized String token() throws IOException {
    long now = clock.getAsLong();
    if (token != null && now < tokenExpiresAt) {
      return token;
    }
    String form = "grant_type=client_credentials&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
        + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
    HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUrl))
        .timeout(timeout)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
        .build();
    JsonNode out = JSON.readTree(send(req, "token"));
    token = out.path("access_token").asText(null);
    if (token == null) {
      throw new IOException("Không lấy được token gọi tenant-registry");
    }
    // Làm mới trước hạn 30 giây.
    tokenExpiresAt = now + Math.max(0, out.path("expires_in").asLong(60) - 30) * 1000;
    return token;
  }

  private byte[] send(HttpRequest req, String what) throws IOException {
    try {
      HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
      if (res.statusCode() / 100 != 2) {
        if (res.statusCode() == 401 && !"token".equals(what)) {
          synchronized (this) {
            token = null;
          }
        }
        throw new IOException("Lời gọi " + what + " trả mã " + res.statusCode());
      }
      return res.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Bị ngắt khi gọi " + what, e);
    }
  }

  private static String trim(String v) {
    return v == null ? "" : v.trim();
  }
}
