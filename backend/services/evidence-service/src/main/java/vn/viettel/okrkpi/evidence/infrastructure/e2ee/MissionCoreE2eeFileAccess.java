package vn.viettel.okrkpi.evidence.infrastructure.e2ee;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.evidence.application.E2eeFileAccess;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Cổng nội bộ MOD-03 {@code GET /internal/v1/e2ee-access/evidence-files/{objectId}?personId=}
 * (CORE-07-06, ngoài hợp đồng công khai như cổng {@code esign-sources}, suy luận ở wiki trang 14):
 * token client dịch vụ của evidence-service (client credentials), tenant đích trong {@code
 * X-Tenant-Id}. Lỗi kết nối hoặc phản hồi sai dạng thì đóng an toàn (503).
 */
public class MissionCoreE2eeFileAccess implements E2eeFileAccess {

  static final String PATH =
      "/internal/v1/e2ee-access/evidence-files/{objectId}?personId={personId}";

  private static final ParameterizedTypeReference<Map<String, Object>> MAP =
      new ParameterizedTypeReference<>() {};

  private final RestClient http;
  private final Token token;

  public MissionCoreE2eeFileAccess(RestClient http, Token token) {
    this.http = Objects.requireNonNull(http, "http");
    this.token = Objects.requireNonNull(token, "token");
  }

  @Override
  public Decision check(UUID tenantId, UUID objectId, UUID personId) {
    try {
      Map<String, Object> b =
          http.get()
              .uri(PATH, objectId, personId)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.bearer())
              .header("X-Tenant-Id", tenantId.toString())
              .retrieve()
              .body(MAP);
      if (b == null
          || !(b.get("wrapped") instanceof Boolean wrapped)
          || !(b.get("active") instanceof Boolean active)) {
        throw new RestClientException("mission-core trả kết quả sai dạng");
      }
      return new Decision(wrapped, active);
    } catch (RestClientException e) {
      throw new DependencyUnavailableException(
          "mission-core", DependencyUnavailableException.Reason.IO_ERROR, Duration.ofSeconds(5), e);
    }
  }

  /** Token client dịch vụ (client credentials), giữ tới 30 giây trước khi hết hạn. */
  public static final class Token {

    private final RestClient http;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private String value;
    private Instant expiry = Instant.EPOCH;

    public Token(
        RestClient http, String tokenUrl, String clientId, String clientSecret, Clock clock) {
      this.http = Objects.requireNonNull(http, "http");
      this.tokenUrl = Objects.requireNonNull(tokenUrl, "tokenUrl");
      this.clientId = Objects.requireNonNull(clientId, "clientId");
      this.clientSecret = clientSecret == null ? "" : clientSecret;
      this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized String bearer() {
      if (value != null && clock.instant().isBefore(expiry)) {
        return value;
      }
      LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("grant_type", "client_credentials");
      form.add("client_id", clientId);
      form.add("client_secret", clientSecret);
      Map<String, Object> body =
          http.post()
              .uri(tokenUrl)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(MAP);
      if (body == null || !(body.get("access_token") instanceof String access)) {
        throw new RestClientException("Keycloak không trả access_token");
      }
      long ttl = body.get("expires_in") instanceof Number n ? n.longValue() : 60;
      value = access;
      expiry = clock.instant().plusSeconds(Math.max(ttl - 30, 5));
      return value;
    }
  }
}
