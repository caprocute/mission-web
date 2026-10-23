package vn.viettel.okrkpi.integ.infrastructure.hrm;

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

/**
 * Token dịch vụ của integration-gateway (OAuth 2.0 client credentials, Keycloak) để gọi cổng nội bộ
 * của org-service; giữ tới 30 giây trước khi hết hạn, cùng cách {@code ServiceToken} của
 * analytics-api. Không cấu hình địa chỉ token thì không gửi token (giả lập, kiểm thử).
 */
public class ServiceToken {

  private final HttpClient http;
  private final ObjectMapper json;
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final Clock clock;
  private String token;
  private Instant expiry = Instant.EPOCH;

  public ServiceToken(
      HttpClient http,
      ObjectMapper json,
      String tokenUrl,
      String clientId,
      String clientSecret,
      Clock clock) {
    this.http = http;
    this.json = json;
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.clock = clock;
  }

  public boolean enabled() {
    return tokenUrl != null && !tokenUrl.isBlank();
  }

  public synchronized String bearer() throws IOException {
    if (token != null && clock.instant().isBefore(expiry)) {
      return token;
    }
    String form =
        "grant_type=client_credentials&client_id="
            + URLEncoder.encode(clientId == null ? "" : clientId, StandardCharsets.UTF_8)
            + "&client_secret="
            + URLEncoder.encode(clientSecret == null ? "" : clientSecret, StandardCharsets.UTF_8);
    HttpResponse<byte[]> res;
    try {
      res =
          http.send(
              HttpRequest.newBuilder(URI.create(tokenUrl))
                  .timeout(Duration.ofSeconds(5))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(form))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Bị ngắt khi lấy token dịch vụ", e);
    }
    if (res.statusCode() / 100 != 2) {
      throw new IOException("Keycloak trả mã " + res.statusCode() + " khi lấy token dịch vụ");
    }
    JsonNode body = json.readTree(res.body());
    String access = body.path("access_token").asText(null);
    if (access == null) {
      throw new IOException("Keycloak không trả token dịch vụ");
    }
    long ttl = body.path("expires_in").asLong(60);
    token = access;
    expiry = clock.instant().plusSeconds(Math.max(ttl - 30, 5));
    return token;
  }
}
