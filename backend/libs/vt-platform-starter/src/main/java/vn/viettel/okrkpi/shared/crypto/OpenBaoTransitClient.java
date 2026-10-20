package vn.viettel.okrkpi.shared.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Client HTTP tối giản gọi OpenBao Transit Engine để bọc (encrypt) và mở bọc (decrypt) DEK bằng KEK
 * của từng tenant ({@code kek-<tenant_id>}) theo ADR-06, ADR-17, TL-01 và TL-04.
 *
 * <ul>
 *   <li>Timeout kết nối 500 ms ({@link
 *       vn.viettel.okrkpi.shared.resilience.PlatformTimeouts#OPENBAO_CONNECT}) và timeout đọc 2 s
 *       ({@link vn.viettel.okrkpi.shared.resilience.PlatformTimeouts#OPENBAO_READ}).
 *   <li>Xác thực bằng token đọc từ tệp do CSI/ESO gắn ({@link CryptoProperties#getTokenFile()})
 *       hoặc đăng nhập qua Kubernetes Auth ({@code /v1/auth/<mount>/login}) bằng JWT ServiceAccount
 *       từ tệp ({@link CryptoProperties#getServiceAccountTokenFile()}).
 *   <li>Không bao giờ nhận khoá mật mã hay token dạng rõ từ thuộc tính cấu hình.
 * </ul>
 */
public class OpenBaoTransitClient {

  private static final Duration K8S_TOKEN_RENEW_MARGIN = Duration.ofSeconds(30);

  private final CryptoProperties properties;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  private volatile String cachedK8sToken;
  private volatile Instant cachedK8sTokenExpiry = Instant.EPOCH;

  public OpenBaoTransitClient(CryptoProperties properties, ObjectMapper objectMapper) {
    this(properties, objectMapper, Clock.systemUTC());
  }

  public OpenBaoTransitClient(CryptoProperties properties, ObjectMapper objectMapper, Clock clock) {
    this(
        properties,
        objectMapper,
        clock,
        HttpClient.newBuilder()
            .connectTimeout(Objects.requireNonNull(properties, "properties").getConnectTimeout())
            .version(HttpClient.Version.HTTP_1_1)
            .build());
  }

  public OpenBaoTransitClient(
      CryptoProperties properties, ObjectMapper objectMapper, Clock clock, HttpClient httpClient) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.objectMapper =
        objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
  }

  /** Tên khoá KEK trên OpenBao Transit cho một tenant: {@code kek-<tenant_id>}. */
  public String kekName(UUID tenantId) {
    Objects.requireNonNull(tenantId, "tenantId");
    return properties.getKekPrefix() + tenantId;
  }

  /** Đường dẫn tham chiếu KEK lưu trong cột {@code keyring.dek.kek_ref}. */
  public String kekRef(UUID tenantId) {
    Objects.requireNonNull(tenantId, "tenantId");
    return properties.getTransitMount() + "/keys/" + kekName(tenantId);
  }

  /**
   * Đảm bảo KEK {@code kek-<tenant_id>} tồn tại trên OpenBao Transit (khởi tạo khoá {@code
   * aes256-gcm96}).
   */
  public void ensureKek(UUID tenantId) {
    Objects.requireNonNull(tenantId, "tenantId");
    String path = "/v1/" + properties.getTransitMount() + "/keys/" + kekName(tenantId);
    byte[] payload = serializeJson(Map.of("type", "aes256-gcm96"));
    HttpResponse<byte[]> response = sendAuthenticatedPost(path, payload);
    int status = response.statusCode();
    if (status != 200 && status != 204) {
      throw new CryptoException(
          "OpenBao Transit từ chối tạo/kiểm tra KEK cho tenant "
              + tenantId
              + " (HTTP "
              + status
              + ")");
    }
  }

  /**
   * Bọc (wrap) mảng byte DEK 32 byte bằng KEK của tenant qua {@code
   * /v1/transit/encrypt/kek-<tenant>}.
   *
   * @param tenantId định danh tenant
   * @param plaintextDek khoá DEK rõ 32 byte
   * @return mảng byte bản mã do OpenBao trả về (ví dụ {@code vault:v1:...} dạng UTF-8)
   */
  public byte[] wrapDek(UUID tenantId, byte[] plaintextDek) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(plaintextDek, "plaintextDek");
    if (plaintextDek.length != CryptoProvider.KEY_LENGTH_BYTES) {
      throw new CryptoException(
          "DEK cần bọc phải dài đúng " + CryptoProvider.KEY_LENGTH_BYTES + " byte");
    }
    if (properties.isAutoCreateKek()) {
      ensureKek(tenantId);
    }
    String b64Plaintext = Base64.getEncoder().encodeToString(plaintextDek);
    String path = "/v1/" + properties.getTransitMount() + "/encrypt/" + kekName(tenantId);
    byte[] payload = serializeJson(Map.of("plaintext", b64Plaintext));
    HttpResponse<byte[]> response = sendAuthenticatedPost(path, payload);
    if (response.statusCode() != 200) {
      throw new CryptoException(
          "OpenBao Transit encrypt thất bại cho tenant "
              + tenantId
              + " (HTTP "
              + response.statusCode()
              + ")");
    }
    JsonNode root = parseJson(response.body());
    String ciphertext = root.path("data").path("ciphertext").asText(null);
    if (ciphertext == null || ciphertext.isBlank()) {
      throw new CryptoException("Phản hồi OpenBao Transit encrypt thiếu data.ciphertext");
    }
    return ciphertext.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Mở bọc (unwrap) DEK đã bọc bằng KEK của tenant qua {@code /v1/transit/decrypt/kek-<tenant>}.
   *
   * @param tenantId định danh tenant
   * @param wrappedDek dữ liệu {@code wrapped_dek} từ bảng {@code keyring.dek}
   * @return mảng byte DEK rõ 32 byte
   */
  public byte[] unwrapDek(UUID tenantId, byte[] wrappedDek) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(wrappedDek, "wrappedDek");
    if (wrappedDek.length == 0) {
      throw new CryptoException("wrappedDek không được rỗng");
    }
    String ciphertext = new String(wrappedDek, StandardCharsets.UTF_8);
    String path = "/v1/" + properties.getTransitMount() + "/decrypt/" + kekName(tenantId);
    byte[] payload = serializeJson(Map.of("ciphertext", ciphertext));
    HttpResponse<byte[]> response = sendAuthenticatedPost(path, payload);
    if (response.statusCode() != 200) {
      throw new CryptoException(
          "OpenBao Transit decrypt thất bại cho tenant "
              + tenantId
              + " (HTTP "
              + response.statusCode()
              + ")");
    }
    JsonNode root = parseJson(response.body());
    String b64Plaintext = root.path("data").path("plaintext").asText(null);
    if (b64Plaintext == null || b64Plaintext.isBlank()) {
      throw new CryptoException("Phản hồi OpenBao Transit decrypt thiếu data.plaintext");
    }
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(b64Plaintext);
    } catch (IllegalArgumentException e) {
      throw new CryptoException("data.plaintext từ OpenBao Transit không phải Base64 hợp lệ", e);
    }
    if (raw.length != CryptoProvider.KEY_LENGTH_BYTES) {
      DekCache.zeroKey(raw);
      throw new CryptoException(
          "DEK sau khi mở bọc từ OpenBao có độ dài không hợp lệ: " + raw.length);
    }
    return raw;
  }

  private HttpResponse<byte[]> sendAuthenticatedPost(String path, byte[] body) {
    String token = resolveToken(false);
    HttpResponse<byte[]> response = doPost(path, body, token);
    if (response.statusCode() == 403 && properties.getKubernetesRole() != null) {
      // Token Kubernetes có thể vừa bị thu hồi sớm; thử đăng nhập lại một lần.
      String refreshed = resolveToken(true);
      response = doPost(path, body, refreshed);
    }
    return response;
  }

  private HttpResponse<byte[]> doPost(String path, byte[] body, String token) {
    URI uri = resolveUri(path);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(properties.getReadTimeout())
            .header("Content-Type", "application/json")
            .header("X-Vault-Request", "true")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (token != null && !token.isEmpty()) {
      builder.header("X-Vault-Token", token);
    }
    try {
      return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CryptoException("Luồng bị ngắt khi gọi OpenBao Transit", e);
    } catch (IOException e) {
      throw new CryptoException("Lỗi kết nối hoặc quá hạn khi gọi OpenBao Transit", e);
    }
  }

  private String resolveToken(boolean forceRefresh) {
    Path tokenFile = properties.getTokenFile();
    if (tokenFile != null) {
      return readSecretFile(tokenFile, "tokenFile");
    }
    String role = properties.getKubernetesRole();
    Path saTokenFile = properties.getServiceAccountTokenFile();
    if (role != null && saTokenFile != null) {
      return loginWithKubernetes(role, saTokenFile, forceRefresh);
    }
    throw new CryptoException(
        "Chưa cấu hình xác thực OpenBao: cần đặt okrkpi.crypto.token-file hoặc"
            + " okrkpi.crypto.kubernetes-role kèm service-account-token-file");
  }

  private synchronized String loginWithKubernetes(
      String role, Path saTokenFile, boolean forceRefresh) {
    Instant now = clock.instant();
    if (!forceRefresh && cachedK8sToken != null && now.isBefore(cachedK8sTokenExpiry)) {
      return cachedK8sToken;
    }
    String jwt = readSecretFile(saTokenFile, "serviceAccountTokenFile");
    String loginPath = "/v1/auth/" + properties.getKubernetesMount() + "/login";
    byte[] payload = serializeJson(Map.of("role", role, "jwt", jwt));
    HttpResponse<byte[]> response = doPost(loginPath, payload, null);
    if (response.statusCode() != 200) {
      throw new CryptoException(
          "Đăng nhập OpenBao Kubernetes auth thất bại (HTTP " + response.statusCode() + ")");
    }
    JsonNode root = parseJson(response.body());
    JsonNode auth = root.path("auth");
    String clientToken = auth.path("client_token").asText(null);
    if (clientToken == null || clientToken.isBlank()) {
      throw new CryptoException("Phản hồi đăng nhập OpenBao thiếu auth.client_token");
    }
    long leaseSeconds = auth.path("lease_duration").asLong(300L);
    Duration effectiveTtl =
        Duration.ofSeconds(Math.max(5L, leaseSeconds)).minus(K8S_TOKEN_RENEW_MARGIN);
    if (effectiveTtl.isNegative() || effectiveTtl.isZero()) {
      effectiveTtl = Duration.ofSeconds(5);
    }
    this.cachedK8sToken = clientToken.trim();
    this.cachedK8sTokenExpiry = now.plus(effectiveTtl);
    return this.cachedK8sToken;
  }

  private static String readSecretFile(Path file, String label) {
    try {
      String content = Files.readString(file, StandardCharsets.UTF_8).trim();
      if (content.isEmpty()) {
        throw new CryptoException("Tệp xác thực OpenBao (" + label + ") rỗng");
      }
      return content;
    } catch (IOException e) {
      throw new CryptoException("Không đọc được tệp xác thực OpenBao (" + label + ")", e);
    }
  }

  private URI resolveUri(String path) {
    String base = properties.getOpenBaoBaseUrl().toString();
    if (base.endsWith("/") && path.startsWith("/")) {
      return URI.create(base.substring(0, base.length() - 1) + path);
    }
    if (!base.endsWith("/") && !path.startsWith("/")) {
      return URI.create(base + "/" + path);
    }
    return URI.create(base + path);
  }

  private byte[] serializeJson(Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (IOException e) {
      throw new CryptoException("Không tuần tự hoá được JSON gọi OpenBao", e);
    }
  }

  private JsonNode parseJson(byte[] bytes) {
    try {
      return objectMapper.readTree(bytes);
    } catch (IOException e) {
      throw new CryptoException("Phản hồi từ OpenBao không phải JSON hợp lệ", e);
    }
  }
}
