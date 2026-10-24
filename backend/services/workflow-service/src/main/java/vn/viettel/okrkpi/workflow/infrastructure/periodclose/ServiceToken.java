package vn.viettel.okrkpi.workflow.infrastructure.periodclose;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Token client dịch vụ của workflow-service (OAuth 2.0 client credentials, Keycloak) để saga chốt
 * kỳ gọi cổng nội bộ của MOD-08; giữ tới 30 giây trước khi hết hạn, như {@code ServiceToken} của
 * analytics-api.
 */
final class ServiceToken {

  private static final ParameterizedTypeReference<Map<String, Object>> MAP =
      new ParameterizedTypeReference<>() {};

  private final RestClient http;
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final Clock clock;
  private String token;
  private Instant expiry = Instant.EPOCH;

  ServiceToken(
      RestClient http, String tokenUrl, String clientId, String clientSecret, Clock clock) {
    this.http = http;
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.clock = clock;
  }

  synchronized String bearer() {
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
