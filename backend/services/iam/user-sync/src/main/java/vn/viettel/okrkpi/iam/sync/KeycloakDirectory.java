package vn.viettel.okrkpi.iam.sync;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link Directory} qua Admin REST API của Keycloak 26. Mỗi lời gọi có thời hạn {@code timeout} (3 giây) và đi qua
 * bộ ngắt mạch: Keycloak lỗi liên tiếp thì ngắt, thông điệp chuyển sang thử lại có giãn cách của listener rồi vào thư
 * chết. Tạo người dùng dùng {@code partialImport} vì {@code POST /users} không giữ {@code id} truyền vào.
 */
public class KeycloakDirectory implements Directory {

  private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {
  };
  private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
  };

  private final RestClient http;
  private final KeycloakProperties props;
  private final CircuitBreaker breaker;
  private final Clock clock;
  private final Map<UUID, String> organizations = new ConcurrentHashMap<>();
  private String token;
  private Instant tokenExpiry = Instant.MIN;

  public KeycloakDirectory(KeycloakProperties props, CircuitBreaker breaker, Clock clock) {
    this.props = Objects.requireNonNull(props, "props");
    this.breaker = Objects.requireNonNull(breaker, "breaker");
    this.clock = Objects.requireNonNull(clock, "clock");
    SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
    rf.setConnectTimeout(props.timeout());
    rf.setReadTimeout(props.timeout());
    this.http = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(rf).build();
  }

  @Override
  public Optional<Map<String, Object>> findUser(UUID id) {
    return call(() -> {
      try {
        return Optional.ofNullable(admin().get().uri("/users/{id}", id).retrieve().body(MAP));
      } catch (HttpClientErrorException.NotFound e) {
        return Optional.empty();
      }
    });
  }

  @Override
  public void createUser(Map<String, Object> user) {
    call(() -> admin().post().uri("/partialImport").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("ifResourceExists", "SKIP", "users", List.of(user))).retrieve().toBodilessEntity());
  }

  @Override
  public void updateUser(UUID id, Map<String, Object> user) {
    call(() -> admin().put().uri("/users/{id}", id).contentType(MediaType.APPLICATION_JSON).body(user).retrieve()
        .toBodilessEntity());
  }

  @Override
  public void logout(UUID id) {
    call(() -> admin().post().uri("/users/{id}/logout", id).retrieve().toBodilessEntity());
  }

  @Override
  public Optional<String> organizationOf(UUID tenant) {
    String cached = organizations.get(tenant);
    if (cached != null) {
      return Optional.of(cached);
    }
    Optional<String> found = call(() -> {
      List<Map<String, Object>> page = admin().get()
          .uri(u -> u.path("/organizations").queryParam("q", "tenant_id:" + tenant).queryParam("exact", true)
              .queryParam("briefRepresentation", true).build())
          .retrieve().body(LIST);
      return page == null || page.size() != 1 ? Optional.<String>empty()
          : Optional.of(String.valueOf(page.get(0).get("id")));
    });
    found.ifPresent(id -> organizations.put(tenant, id));
    return found;
  }

  @Override
  public void addMember(String organizationId, UUID userId) {
    call(() -> {
      try {
        return admin().post().uri("/organizations/{org}/members", organizationId)
            .contentType(MediaType.APPLICATION_JSON).body("\"" + userId + "\"").retrieve().toBodilessEntity();
      } catch (HttpClientErrorException.Conflict e) {
        return null;
      }
    });
  }

  private RestClient admin() {
    String bearer = bearer();
    return http.mutate().baseUrl(props.baseUrl() + "/admin/realms/" + props.realm())
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).build();
  }

  private synchronized String bearer() {
    if (token != null && clock.instant().isBefore(tokenExpiry)) {
      return token;
    }
    LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", props.clientId());
    form.add("client_secret", props.clientSecret());
    Map<String, Object> body = http.post().uri("/realms/{realm}/protocol/openid-connect/token", props.realm())
        .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(MAP);
    if (body == null || !(body.get("access_token") instanceof String access)) {
      throw new RestClientException("Keycloak không trả access_token");
    }
    long ttl = body.get("expires_in") instanceof Number n ? n.longValue() : 60;
    token = access;
    tokenExpiry = clock.instant().plusSeconds(Math.max(ttl - 30, 5));
    return token;
  }

  private <T> T call(Supplier<T> work) {
    try {
      return breaker.executeSupplier(work);
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(401))) {
        synchronized (this) {
          token = null;
        }
        throw new TransientException("Keycloak từ chối token dịch vụ", e);
      }
      if (e.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(429))) {
        throw new TransientException("Keycloak quá tải", e);
      }
      throw new PermanentException("Keycloak từ chối yêu cầu: " + e.getStatusCode().value());
    } catch (RestClientException | CallNotPermittedException e) {
      throw new TransientException("Keycloak không phản hồi", e);
    }
  }
}
