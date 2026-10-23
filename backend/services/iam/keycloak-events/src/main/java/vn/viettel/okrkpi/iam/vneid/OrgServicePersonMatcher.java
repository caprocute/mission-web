package vn.viettel.okrkpi.iam.vneid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Gọi {@code POST /internal/v1/org/tenants/{tenantId}/persons/national-id-match} của org-service bằng token tài khoản
 * dịch vụ (client credentials, vai trò realm {@code svc_iam}). Số định danh đi trong thân yêu cầu qua kênh nội bộ
 * (mTLS của lưới dịch vụ), org-service tự tính chỉ mục mù bằng khoá của tenant; không ghi nhật ký thân yêu cầu.
 */
final class OrgServicePersonMatcher implements PersonMatcher {

  static final String PATH = "/internal/v1/org/tenants/%s/persons/national-id-match";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient http;
  private final VneidSettings settings;
  private String token;
  private long tokenExpiresAt;

  OrgServicePersonMatcher(VneidSettings settings) {
    this.settings = settings;
    this.http = HttpClient.newBuilder().connectTimeout(settings.orgTimeout())
        .followRedirects(HttpClient.Redirect.NEVER).build();
  }

  @Override
  public Match match(UUID tenantId, String nationalId) throws IOException {
    String body = JSON.writeValueAsString(Map.of("nationalId", nationalId));
    HttpRequest req = HttpRequest.newBuilder(URI.create(settings.orgServiceUrl() + PATH.formatted(tenantId)))
        .timeout(settings.orgTimeout())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header("Authorization", "Bearer " + token())
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    JsonNode out = JSON.readTree(send(req, "org-service"));
    String id = out.path("personId").asText("");
    return new Match(id.isBlank() ? null : UUID.fromString(id), out.path("matches").asInt(0));
  }

  private synchronized String token() throws IOException {
    long now = System.currentTimeMillis();
    if (token != null && now < tokenExpiresAt) {
      return token;
    }
    String form = "grant_type=client_credentials&client_id="
        + URLEncoder.encode(settings.brokerClientId(), StandardCharsets.UTF_8)
        + "&client_secret=" + URLEncoder.encode(settings.brokerClientSecret(), StandardCharsets.UTF_8);
    HttpRequest req = HttpRequest.newBuilder(URI.create(settings.brokerTokenUrl()))
        .timeout(settings.orgTimeout())
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
        .build();
    JsonNode out = JSON.readTree(send(req, "token"));
    token = out.path("access_token").asText(null);
    if (token == null) {
      throw new IOException("Không lấy được token tài khoản dịch vụ môi giới");
    }
    // Làm mới trước hạn 30 giây.
    tokenExpiresAt = now + Math.max(0, out.path("expires_in").asLong(60) - 30) * 1000;
    return token;
  }

  private byte[] send(HttpRequest req, String what) throws IOException {
    try {
      HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
      if (res.statusCode() / 100 != 2) {
        if (res.statusCode() == 401 && "org-service".equals(what)) {
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
}
