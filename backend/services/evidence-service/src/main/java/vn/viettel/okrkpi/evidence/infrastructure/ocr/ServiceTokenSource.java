package vn.viettel.okrkpi.evidence.infrastructure.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Token dịch vụ Keycloak (client credentials) của evidence-service để gọi ai-gateway; giữ trong bộ
 * nhớ tới gần hết hạn. Không cấu hình {@code token-url} thì không gửi token (bản giả lập).
 */
class ServiceTokenSource {

  private final HttpClient http;
  private final ObjectMapper json;
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final Duration timeout;
  private final Clock clock;
  private String token;
  private Instant expiry = Instant.EPOCH;

  ServiceTokenSource(
      HttpClient http,
      ObjectMapper json,
      String tokenUrl,
      String clientId,
      String clientSecret,
      Duration timeout,
      Clock clock) {
    this.http = http;
    this.json = json;
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.timeout = timeout;
    this.clock = clock;
  }

  synchronized Optional<String> bearer() throws IOException {
    if (tokenUrl == null || tokenUrl.isBlank()) {
      return Optional.empty();
    }
    if (token != null && clock.instant().isBefore(expiry)) {
      return Optional.of(token);
    }
    String form =
        "grant_type=client_credentials&client_id="
            + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + "&client_secret="
            + URLEncoder.encode(clientSecret == null ? "" : clientSecret, StandardCharsets.UTF_8);
    HttpResponse<byte[]> res;
    try {
      res =
          http.send(
              HttpRequest.newBuilder(URI.create(tokenUrl))
                  .timeout(timeout)
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(form))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Bị ngắt khi lấy token dịch vụ", e);
    }
    if (res.statusCode() != 200) {
      throw new IOException("Keycloak trả mã " + res.statusCode() + " khi cấp token dịch vụ");
    }
    JsonNode body = json.readTree(res.body());
    String access = body.path("access_token").asText(null);
    if (access == null) {
      throw new IOException("Keycloak không trả access_token");
    }
    long ttl = body.path("expires_in").asLong(60);
    token = access;
    expiry = clock.instant().plusSeconds(Math.max(ttl - 30, 5));
    return Optional.of(token);
  }
}
