package vn.viettel.okrkpi.scoring.infrastructure.client;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Token client dịch vụ của scoring-service (OAuth 2.0 client credentials, Keycloak) để đọc Policy
 * Pack của MOD-05 và đầu vào hồ sơ của MOD-04 trên mission-core; giữ tới 30 giây trước khi hết hạn,
 * như {@code ServiceToken} của workflow-service. Chưa khai client thì không gắn token (kiểm thử).
 */
public final class ServiceToken {

  private static final ParameterizedTypeReference<Map<String, Object>> MAP =
      new ParameterizedTypeReference<>() {};

  private final RestClient http;
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final Clock clock;
  private String token;
  private Instant expiry = Instant.EPOCH;

  public ServiceToken(
      RestClient http, String tokenUrl, String clientId, String clientSecret, Clock clock) {
    this.http = http;
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.clock = clock;
  }

  /** Có cấu hình client để xin token hay không. */
  public boolean configured() {
    return tokenUrl != null && !tokenUrl.isBlank() && clientId != null && !clientId.isBlank();
  }

  /** Bộ chặn gắn {@code Authorization: Bearer} vào mọi lời gọi khi đã cấu hình client. */
  public ClientHttpRequestInterceptor interceptor() {
    return (request, body, execution) -> {
      if (configured() && !request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
        request.getHeaders().setBearerAuth(bearer());
      }
      return execution.execute(request, body);
    };
  }

  public synchronized String bearer() {
    if (token != null && clock.instant().isBefore(expiry)) {
      return token;
    }
    LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret == null ? "" : clientSecret);
    Map<String, Object> body;
    try {
      body =
          http.post()
              .uri(tokenUrl)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(MAP);
    } catch (RestClientException e) {
      throw new DependencyUnavailableException(
          "keycloak", DependencyUnavailableException.Reason.IO_ERROR, null, e);
    }
    if (body == null || !(body.get("access_token") instanceof String access)) {
      throw new DependencyUnavailableException(
          "keycloak", DependencyUnavailableException.Reason.IO_ERROR, null, null);
    }
    long ttl = body.get("expires_in") instanceof Number n ? n.longValue() : 60;
    token = access;
    expiry = clock.instant().plusSeconds(Math.max(ttl - 30, 5));
    return token;
  }
}
