package vn.viettel.okrkpi.org.infrastructure.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.org.application.port.IntegrationUnavailableException;

/**
 * Phiên gọi Admin API Keycloak trong mạng quản trị bằng client credentials riêng của org-service (vai trò
 * {@code realm-management} {@code view-users}, {@code manage-users}) cho {@link KeycloakAccountAdapter}; cùng cấu
 * hình với {@link KeycloakContextAdapter}. Chưa cấu hình {@code okrkpi.org.keycloak.base-url} thì mọi lời gọi trả
 * 503.
 */
@Component
class KeycloakAdmin {

  private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
  };

  private final RestClient http;
  private final boolean configured;
  private final String baseUrl;
  private final String realm;
  private final String clientId;
  private final String clientSecret;
  private final Clock clock;
  private String token;
  private Instant tokenExpiry = Instant.MIN;

  KeycloakAdmin(@Value("${okrkpi.org.keycloak.base-url:}") String baseUrl,
      @Value("${okrkpi.org.keycloak.realm:mission}") String realm,
      @Value("${okrkpi.org.keycloak.client-id:org-service}") String clientId,
      @Value("${okrkpi.org.keycloak.client-secret:}") String clientSecret, Clock clock) {
    this.configured = baseUrl != null && !baseUrl.isBlank();
    this.baseUrl = configured ? baseUrl : "http://keycloak.invalid";
    this.realm = realm;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.clock = clock;
    SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
    rf.setConnectTimeout(Duration.ofSeconds(3));
    rf.setReadTimeout(Duration.ofSeconds(3));
    this.http = RestClient.builder().baseUrl(this.baseUrl).requestFactory(rf).build();
  }

  boolean configured() {
    return configured;
  }

  /** Client đã gắn token, gốc {@code /admin/realms/<realm>}. */
  RestClient admin() {
    require();
    String bearer = bearer();
    return http.mutate().baseUrl(baseUrl + "/admin/realms/" + realm)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).build();
  }

  private synchronized String bearer() {
    if (token != null && clock.instant().isBefore(tokenExpiry)) {
      return token;
    }
    LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    Map<String, Object> body;
    try {
      body = http.post().uri("/realms/{realm}/protocol/openid-connect/token", realm)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(MAP);
    } catch (RestClientException e) {
      throw unavailable(e);
    }
    if (body == null || !(body.get("access_token") instanceof String access)) {
      throw new IntegrationUnavailableException("Keycloak không cấp token dịch vụ", null);
    }
    long ttl = body.get("expires_in") instanceof Number n ? n.longValue() : 60;
    token = access;
    tokenExpiry = clock.instant().plusSeconds(Math.max(ttl - 30, 5));
    return token;
  }

  void require() {
    if (!configured) {
      throw new IntegrationUnavailableException("chưa cấu hình kết nối tới Keycloak", null);
    }
  }

  static IntegrationUnavailableException unavailable(RestClientException e) {
    return new IntegrationUnavailableException("Keycloak không phản hồi", e);
  }
}
