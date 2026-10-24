package vn.viettel.okrkpi.org.infrastructure.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.org.application.port.AccountDirectoryPort;
import vn.viettel.okrkpi.org.application.port.IntegrationUnavailableException;
import vn.viettel.okrkpi.org.domain.OrgConflictException;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;

/**
 * {@link AccountDirectoryPort} qua Admin API Keycloak (DV-04-09, BS-04 ADR-34 mục 2): tạo người dùng kèm mật khẩu
 * tạm buộc đổi và vai trò người dùng cơ bản, sửa họ tên, thư điện tử, bật tắt đăng nhập, đặt lại mật khẩu và thu hồi
 * phiên. Thuộc tính {@code tenant}, {@code tenant_id}, {@code unit_id} khớp protocol mapper {@code mission-context}.
 */
@Component
public class KeycloakAccountAdapter implements AccountDirectoryPort {

  /** Vai trò người dùng cơ bản mà mọi cán bộ có (AC-01); vai trò khác cấp qua MOD-08 (CORE-08-16). */
  static final String BASE_ROLE = "member";
  private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
  };
  private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
      new ParameterizedTypeReference<>() {
      };

  private final KeycloakAdmin keycloak;

  KeycloakAccountAdapter(KeycloakAdmin keycloak) {
    this.keycloak = keycloak;
  }

  @Override
  public UUID create(NewAccount a) {
    keycloak.require();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", a.username());
    body.put("enabled", true);
    String[] name = splitName(a.fullName());
    body.put("lastName", name[0]);
    body.put("firstName", name[1]);
    if (a.email() != null) {
      body.put("email", a.email());
      body.put("emailVerified", false);
    }
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("tenant", List.of(a.tenantId().toString()));
    attrs.put("tenant_id", List.of(a.tenantId().toString()));
    attrs.put("unit_id", List.of(a.unitId().toString()));
    body.put("attributes", attrs);
    body.put("requiredActions", List.of("UPDATE_PASSWORD"));
    body.put("credentials", List.of(Map.of("type", "password", "value", a.temporaryPassword(), "temporary", true)));
    UUID id;
    try {
      ResponseEntity<Void> res = admin().post().uri("/users").contentType(MediaType.APPLICATION_JSON).body(body)
          .retrieve().toBodilessEntity();
      String location = String.valueOf(res.getHeaders().getLocation());
      id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    } catch (HttpClientErrorException.Conflict e) {
      throw new UsernameTakenException();
    } catch (RestClientException e) {
      throw unavailable(e);
    }
    try {
      grantBaseRole(id);
    } catch (RuntimeException e) {
      delete(id);
      throw e;
    }
    return id;
  }

  private void grantBaseRole(UUID id) {
    try {
      // Danh sách vai trò còn gán được chỉ cần view-users, không cần view-realm.
      List<Map<String, Object>> available = admin().get().uri("/users/{id}/role-mappings/realm/available", id)
          .retrieve().body(LIST);
      List<Map<String, Object>> base = new ArrayList<>();
      for (Map<String, Object> r : available == null ? List.<Map<String, Object>>of() : available) {
        if (BASE_ROLE.equals(r.get("name"))) {
          base.add(r);
        }
      }
      if (!base.isEmpty()) {
        admin().post().uri("/users/{id}/role-mappings/realm", id).contentType(MediaType.APPLICATION_JSON).body(base)
            .retrieve().toBodilessEntity();
      }
    } catch (RestClientException e) {
      throw unavailable(e);
    }
  }

  @Override
  public void delete(UUID id) {
    try {
      admin().delete().uri("/users/{id}", id).retrieve().toBodilessEntity();
    } catch (HttpClientErrorException.NotFound e) {
      // đã không còn
    } catch (RestClientException e) {
      throw unavailable(e);
    }
  }

  @Override
  public Optional<DirectoryAccount> find(UUID id) {
    keycloak.require();
    Map<String, Object> u;
    try {
      u = admin().get().uri("/users/{id}", id).retrieve().body(MAP);
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    } catch (RestClientException e) {
      throw unavailable(e);
    }
    if (u == null) {
      return Optional.empty();
    }
    long created = u.get("createdTimestamp") instanceof Number n ? n.longValue() : 0L;
    return Optional.of(new DirectoryAccount(id, String.valueOf(u.get("username")),
        u.get("email") instanceof String e ? e : null, !Boolean.FALSE.equals(u.get("enabled")), created));
  }

  @Override
  public void updateProfile(UUID id, String fullName, String email) {
    Map<String, Object> user = new LinkedHashMap<>(user(id));
    if (fullName != null) {
      String[] name = splitName(fullName);
      user.put("lastName", name[0]);
      user.put("firstName", name[1]);
    }
    if (email != null) {
      user.put("email", email.isBlank() ? null : email);
      user.put("emailVerified", false);
    }
    put(id, user);
  }

  @Override
  public void setEnabled(UUID id, boolean enabled) {
    Map<String, Object> user = new LinkedHashMap<>(user(id));
    user.put("enabled", enabled);
    put(id, user);
    if (!enabled) {
      logout(id);
    }
  }

  @Override
  public void resetPassword(UUID id, String temporaryPassword) {
    keycloak.require();
    try {
      admin().put().uri("/users/{id}/reset-password", id).contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("type", "password", "value", temporaryPassword, "temporary", true))
          .retrieve().toBodilessEntity();
    } catch (HttpClientErrorException.NotFound e) {
      throw new OrgNotFoundException("Không tìm thấy tài khoản đăng nhập của cán bộ.");
    } catch (RestClientException e) {
      throw unavailable(e);
    }
    logout(id);
  }

  private void logout(UUID id) {
    try {
      admin().post().uri("/users/{id}/logout", id).retrieve().toBodilessEntity();
    } catch (RestClientException e) {
      throw unavailable(e);
    }
  }

  private void put(UUID id, Map<String, Object> user) {
    try {
      admin().put().uri("/users/{id}", id).contentType(MediaType.APPLICATION_JSON).body(user).retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.Conflict e) {
      throw new OrgConflictException("ORG-409-EMAIL-TAKEN",
          "Thư điện tử này đã được dùng cho tài khoản khác.");
    } catch (RestClientException e) {
      throw unavailable(e);
    }
  }

  /** Họ tên tiếng Việt: tên là từ cuối, họ và đệm là phần còn lại (như dữ liệu nạp sẵn). */
  static String[] splitName(String fullName) {
    String n = fullName.strip().replaceAll("\\s+", " ");
    int i = n.lastIndexOf(' ');
    return i < 0 ? new String[] {"", n} : new String[] {n.substring(0, i), n.substring(i + 1)};
  }

  private Map<String, Object> user(UUID id) {
    try {
      Map<String, Object> u = admin().get().uri("/users/{id}", id).retrieve().body(MAP);
      return u == null ? Map.of() : u;
    } catch (HttpClientErrorException.NotFound e) {
      throw new OrgNotFoundException("Không tìm thấy tài khoản đăng nhập của cán bộ.");
    } catch (RestClientException e) {
      throw unavailable(e);
    }
  }

  private RestClient admin() {
    return keycloak.admin();
  }

  private static IntegrationUnavailableException unavailable(RestClientException e) {
    return KeycloakAdmin.unavailable(e);
  }
}
