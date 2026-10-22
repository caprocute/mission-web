package vn.viettel.okrkpi.org.infrastructure.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.org.application.port.IdentityContextPort;
import vn.viettel.okrkpi.org.application.port.IntegrationUnavailableException;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;

/**
 * {@link IdentityContextPort} qua Admin API Keycloak trong mạng quản trị, bằng client credentials riêng của
 * org-service (vai trò {@code realm-management} {@code view-users}, {@code manage-users}). Ngữ cảnh nằm ở các thuộc
 * tính {@code mission_context_*} của người dùng (wiki trang 14 mục 23). Chưa cấu hình
 * {@code okrkpi.org.keycloak.base-url} thì trả 503.
 */
@Component
public class KeycloakContextAdapter implements IdentityContextPort {

  static final String UNIT = "mission_context_unit";
  static final String ASSIGNMENT = "mission_context_assignment";
  static final String ROLES = "mission_context_roles";
  static final String FLOWS = "mission_context_flows";
  static final String VALID_TO = "mission_context_valid_to";
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

  public KeycloakContextAdapter(@Value("${okrkpi.org.keycloak.base-url:}") String baseUrl,
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

  @Override
  public Optional<UUID> lastAssignment(UUID personId) {
    if (!configured) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(first(attributes(user(personId)).get(ASSIGNMENT))).map(UUID::fromString);
    } catch (IntegrationUnavailableException | IllegalArgumentException e) {
      // Không chọn sẵn được thì người dùng tự chọn; không chặn trang chọn phân công.
      return Optional.empty();
    }
  }

  @Override
  public void write(UUID personId, StoredContext context) {
    Map<String, Object> user = new LinkedHashMap<>(user(personId));
    Map<String, Object> attrs = attributes(user);
    attrs.put(UNIT, List.of(context.unitId().toString()));
    attrs.put(ASSIGNMENT, List.of(context.assignmentId().toString()));
    attrs.put(ROLES, context.roles());
    attrs.put(FLOWS, context.flows());
    if (context.validTo() == null) {
      attrs.remove(VALID_TO);
    } else {
      attrs.put(VALID_TO, List.of(context.validTo().toString()));
    }
    user.put("attributes", attrs);
    try {
      admin().put().uri("/users/{id}", personId).contentType(MediaType.APPLICATION_JSON).body(user).retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      throw unavailable(e);
    }
  }

  private Map<String, Object> user(UUID personId) {
    require();
    try {
      Map<String, Object> u = admin().get().uri("/users/{id}", personId).retrieve().body(MAP);
      return u == null ? Map.of() : u;
    } catch (HttpClientErrorException.NotFound e) {
      throw new OrgNotFoundException("Không tìm thấy tài khoản đăng nhập của cán bộ.");
    } catch (RestClientException e) {
      throw unavailable(e);
    }
  }

  private RestClient admin() {
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

  @SuppressWarnings("unchecked")
  private static Map<String, Object> attributes(Map<String, Object> user) {
    Object a = user.get("attributes");
    return a instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
  }

  private static String first(Object values) {
    return values instanceof List<?> l && !l.isEmpty() ? String.valueOf(l.get(0)) : null;
  }

  private void require() {
    if (!configured) {
      throw new IntegrationUnavailableException("chưa cấu hình kết nối tới Keycloak", null);
    }
  }

  private static IntegrationUnavailableException unavailable(RestClientException e) {
    return new IntegrationUnavailableException("Keycloak không phản hồi", e);
  }
}
