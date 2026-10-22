package vn.viettel.okrkpi.analytics.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientResponseException;
import vn.viettel.okrkpi.analytics.application.EmbedPort;
import vn.viettel.okrkpi.analytics.domain.Scope;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * Guest token Superset (DV-15-05) qua {@code POST /api/v1/security/guest_token/} bằng tài khoản
 * dịch vụ chỉ có quyền cấp guest token. Phạm vi dữ liệu nằm trong điều kiện RLS {@code __user =
 * '<JWT Cube>'}: Superset gửi điều kiện này xuống Cube SQL API, Cube đổi người dùng sang JWT đó và
 * áp cùng bộ lọc tenant, nhánh tổ chức như REST ({@code analytics/cube/cube.js}). JWT Cube sống
 * bằng guest token và chỉ gồm ký tự base64url và dấu chấm nên không thoát được khỏi chuỗi SQL.
 */
final class SupersetGuestTokens implements EmbedPort {

  private static final Logger LOG = LoggerFactory.getLogger(SupersetGuestTokens.class);

  /** Superset mặc định cho token truy cập sống 15 phút; dùng lại 10 phút. */
  private static final Duration ACCESS_REUSE = Duration.ofMinutes(10);

  private final ResilientClient http;
  private final CubeTokens cube;
  private final String username;
  private final String password;
  private final String publicUrl;
  private final Clock clock;
  private String access;
  private Instant accessUntil = Instant.EPOCH;

  SupersetGuestTokens(
      ResilientClient http,
      CubeTokens cube,
      String username,
      String password,
      String publicUrl,
      Clock clock) {
    this.http = http;
    this.cube = cube;
    this.username = username;
    this.password = password;
    this.publicUrl =
        publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    this.clock = clock;
  }

  @Override
  public String guestToken(Scope scope, UUID embeddedId, Duration lifetime) {
    String clause = "__user = '" + cube.issue(scope, lifetime) + "'";
    Map<String, Object> body =
        Map.of(
            "user", Map.of("username", "khach", "first_name", "Người xem", "last_name", "Nhúng"),
            "resources", List.of(Map.of("type", "dashboard", "id", embeddedId.toString())),
            "rls", List.of(Map.of("clause", clause)));
    try {
      return grant(body, false);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401) {
        return grant(body, true);
      }
      throw failed(e);
    }
  }

  @Override
  public String embedUrl(UUID embeddedId) {
    return publicUrl + "/embedded/" + embeddedId;
  }

  private String grant(Map<String, Object> body, boolean relogin) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(accessToken(relogin));
    h.setContentType(MediaType.APPLICATION_JSON);
    JsonNode res;
    try {
      res =
          http.exchange(HttpMethod.POST, "/api/v1/security/guest_token/", body, h, JsonNode.class);
    } catch (RestClientResponseException e) {
      if (relogin || e.getStatusCode().value() != 401) {
        throw failed(e);
      }
      throw e;
    }
    String token = res == null ? null : res.path("token").asText(null);
    if (token == null || token.isBlank()) {
      throw unavailable(null);
    }
    return token;
  }

  private synchronized String accessToken(boolean refresh) {
    if (!refresh && access != null && clock.instant().isBefore(accessUntil)) {
      return access;
    }
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    JsonNode res;
    try {
      res =
          http.exchange(
              HttpMethod.POST,
              "/api/v1/security/login",
              Map.of(
                  "username", username, "password", password, "provider", "db", "refresh", false),
              h,
              JsonNode.class);
    } catch (RestClientResponseException e) {
      throw failed(e);
    }
    String token = res == null ? null : res.path("access_token").asText(null);
    if (token == null || token.isBlank()) {
      throw unavailable(null);
    }
    access = token;
    accessUntil = clock.instant().plus(ACCESS_REUSE);
    return access;
  }

  private static DependencyUnavailableException failed(RestClientResponseException e) {
    LOG.warn("Superset trả mã {} khi cấp guest token", e.getStatusCode().value());
    return unavailable(e);
  }

  private static DependencyUnavailableException unavailable(Throwable cause) {
    return new DependencyUnavailableException(
        "superset", DependencyUnavailableException.Reason.IO_ERROR, Duration.ofSeconds(5), cause);
  }
}
