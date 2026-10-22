package vn.viettel.okrkpi.shared.infrastructure.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Client Admin REST API của Keycloak 26, bằng tài khoản dịch vụ (client credentials) có vai trò
 * {@code realm-management} {@code view-users}, {@code manage-users}, {@code query-users} và {@code
 * view-realm} (đọc thành viên của vai trò realm). Quản trị tenant là người giữ vai trò realm {@code
 * adminRole} có thuộc tính {@code tenant_id} trùng tenant.
 */
public class KeycloakAdminClient {

  private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
      new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<Map<String, Object>> MAP =
      new ParameterizedTypeReference<>() {};
  private static final int PAGE = 100;

  private final RestClient http;
  private final KeycloakAdminProperties props;
  private final Clock clock;
  private String token;
  private Instant tokenExpiry = Instant.MIN;

  public KeycloakAdminClient(
      RestClient.Builder builder, KeycloakAdminProperties props, Clock clock) {
    this.props = Objects.requireNonNull(props, "props");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.http = builder.baseUrl(props.baseUrl()).build();
  }

  public int revokeAllSessions(UUID userId) {
    return call(
        () -> {
          List<Map<String, Object>> sessions =
              admin().get().uri("/users/{id}/sessions", userId).retrieve().body(LIST);
          admin().post().uri("/users/{id}/logout", userId).retrieve().toBodilessEntity();
          return sessions == null ? 0 : sessions.size();
        });
  }

  public void requirePasswordChange(UUID userId) {
    call(
        () -> {
          Map<String, Object> user = admin().get().uri("/users/{id}", userId).retrieve().body(MAP);
          Map<String, Object> update = new LinkedHashMap<>(user == null ? Map.of() : user);
          List<Object> actions = new ArrayList<>();
          if (update.get("requiredActions") instanceof List<?> existing) {
            actions.addAll(existing);
          }
          if (!actions.contains("UPDATE_PASSWORD")) {
            actions.add("UPDATE_PASSWORD");
          }
          update.put("requiredActions", actions);
          admin()
              .put()
              .uri("/users/{id}", userId)
              .contentType(MediaType.APPLICATION_JSON)
              .body(update)
              .retrieve()
              .toBodilessEntity();
          return null;
        });
  }

  public List<UUID> tenantAdmins(TenantId tenant) {
    return roleHolders(tenant, props.adminRole());
  }

  /** Người giữ vai trò realm {@code role} có thuộc tính {@code tenant_id} trùng tenant. */
  public List<UUID> roleHolders(TenantId tenant, String role) {
    String wanted = tenant.value().toString();
    return call(
        () -> {
          List<UUID> out = new ArrayList<>();
          for (int first = 0; ; first += PAGE) {
            int from = first;
            List<Map<String, Object>> page =
                admin()
                    .get()
                    .uri(
                        u ->
                            u.path("/roles/{role}/users")
                                .queryParam("briefRepresentation", false)
                                .queryParam("first", from)
                                .queryParam("max", PAGE)
                                .build(role))
                    .retrieve()
                    .body(LIST);
            if (page == null || page.isEmpty()) {
              return out;
            }
            for (Map<String, Object> u : page) {
              if (u.get("attributes") instanceof Map<?, ?> attrs
                  && attrs.get("tenant_id") instanceof List<?> values
                  && values.contains(wanted)) {
                out.add(UUID.fromString(String.valueOf(u.get("id"))));
              }
            }
            if (page.size() < PAGE) {
              return out;
            }
          }
        });
  }

  public String passwordChangeUrl() {
    return "/realms/" + props.realm() + "/login-actions/required-action?execution=UPDATE_PASSWORD";
  }

  /**
   * Token client-credentials của mission-core để gọi dịch vụ nội bộ có vai trò {@code
   * service_client} (CORE-08-05 gọi {@code startPeriodCloseSaga}).
   */
  public String serviceToken() {
    return call(this::bearer);
  }

  private RestClient admin() {
    String bearer = bearer();
    return http.mutate()
        .baseUrl(props.baseUrl() + "/admin/realms/" + props.realm())
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
        .build();
  }

  private synchronized String bearer() {
    if (token != null && clock.instant().isBefore(tokenExpiry)) {
      return token;
    }
    LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", props.clientId());
    form.add("client_secret", props.clientSecret());
    Map<String, Object> body =
        http.post()
            .uri("/realms/{realm}/protocol/openid-connect/token", props.realm())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(MAP);
    if (body == null || !(body.get("access_token") instanceof String access)) {
      throw new RestClientException("Keycloak không trả access_token");
    }
    long ttl = body.get("expires_in") instanceof Number n ? n.longValue() : 60;
    token = access;
    tokenExpiry = clock.instant().plusSeconds(Math.max(ttl - 30, 5));
    return token;
  }

  private <T> T call(java.util.function.Supplier<T> work) {
    try {
      return work.get();
    } catch (HttpClientErrorException.Unauthorized e) {
      synchronized (this) {
        token = null;
      }
      throw unavailable(e);
    } catch (RestClientException e) {
      throw unavailable(e);
    }
  }

  private static DependencyUnavailableException unavailable(Exception e) {
    return new DependencyUnavailableException(
        "keycloak", DependencyUnavailableException.Reason.IO_ERROR, Duration.ofSeconds(30), e);
  }
}
