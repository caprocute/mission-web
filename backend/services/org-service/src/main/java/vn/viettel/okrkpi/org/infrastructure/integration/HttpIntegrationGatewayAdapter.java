package vn.viettel.okrkpi.org.infrastructure.integration;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.org.application.port.IntegrationGatewayPort;
import vn.viettel.okrkpi.org.application.port.IntegrationUnavailableException;
import vn.viettel.okrkpi.org.domain.OrgConflictException;

/**
 * Cổng nội bộ tới integration-gateway (SVC-13) qua mạng lưới dịch vụ (mTLS của Istio ambient, TL-04). Đường dẫn là
 * suy luận, chốt cùng DV-13-06 (wiki trang 14): {@code POST /internal/v1/integ/sync-runs},
 * {@code POST /internal/v1/integ/sync-runs/{id}/retry}, {@code PUT /internal/v1/integ/mapping-xref}; DV-13-10 thêm
 * {@code POST /internal/v1/integ/cell-transfers} (tenant nguồn ở {@code X-Tenant-Id}). Cổng chỉ nhận token dịch vụ
 * mang vai trò {@code svc_org_service} (HT-07): khi có cấu hình Keycloak, mỗi lời gọi kèm token client credentials
 * của client {@code org-service} (cùng client với {@code KeycloakContextAdapter}), giữ lại tới gần hết hạn.
 */
@Component
public class HttpIntegrationGatewayAdapter implements IntegrationGatewayPort {

  /** Tenant đích của lời gọi: token client credentials không mang claim tenant (okrkpi.tenant.delegating-clients). */
  static final String TENANT_HEADER = "X-Tenant-Id";

  private final RestClient http;
  private final boolean configured;
  private final RestClient keycloak;
  private final String tokenPath;
  private final String clientId;
  private final String clientSecret;
  private final Clock clock;
  private String token;
  private Instant tokenExpiry = Instant.MIN;

  public HttpIntegrationGatewayAdapter(@Value("${okrkpi.org.integration-gateway.url:}") String baseUrl,
      @Value("${okrkpi.org.keycloak.base-url:}") String keycloakUrl,
      @Value("${okrkpi.org.keycloak.realm:mission}") String realm,
      @Value("${okrkpi.org.keycloak.client-id:org-service}") String clientId,
      @Value("${okrkpi.org.keycloak.client-secret:}") String clientSecret, Clock clock) {
    this.configured = baseUrl != null && !baseUrl.isBlank();
    this.keycloak = keycloakUrl == null || keycloakUrl.isBlank() ? null
        : RestClient.builder().baseUrl(keycloakUrl).build();
    this.tokenPath = "/realms/" + realm + "/protocol/openid-connect/token";
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.clock = clock;
    this.http = RestClient.builder().baseUrl(configured ? baseUrl : "http://integration-gateway.invalid")
        .requestInterceptor((request, body, execution) -> {
          String bearer = bearer();
          if (bearer != null) {
            request.getHeaders().setBearerAuth(bearer);
          }
          return execution.execute(request, body);
        }).build();
  }

  /** Token dịch vụ hiện hành; {@code null} khi không cấu hình Keycloak (kiểm thử, môi trường chưa nối mạng lưới). */
  private synchronized String bearer() {
    if (keycloak == null) {
      return null;
    }
    if (token != null && clock.instant().isBefore(tokenExpiry)) {
      return token;
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    Map<?, ?> res;
    try {
      res = keycloak.post().uri(tokenPath).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
          .retrieve().body(Map.class);
    } catch (RestClientException e) {
      throw new IntegrationUnavailableException("Keycloak không cấp token dịch vụ", e);
    }
    if (res == null || !(res.get("access_token") instanceof String access)) {
      throw new IntegrationUnavailableException("Keycloak không cấp token dịch vụ", null);
    }
    long ttl = res.get("expires_in") instanceof Number n ? n.longValue() : 60;
    token = access;
    tokenExpiry = clock.instant().plusSeconds(Math.max(ttl - 30, 5));
    return token;
  }

  @Override
  public void requestRun(UUID tenantId, UUID runId, String connector) {
    post(tenantId, "/internal/v1/integ/sync-runs", Map.of("tenantId", tenantId, "runId", runId, "connector", connector));
  }

  @Override
  public void retryDeadLetters(UUID tenantId, UUID runId, UUID retryRunId, String connector) {
    post(tenantId, "/internal/v1/integ/sync-runs/" + runId + "/retry",
        Map.of("tenantId", tenantId, "retryRunId", retryRunId, "connector", connector));
  }

  @Override
  public void mapXref(UUID tenantId, String connector, String externalId, UUID personId) {
    putXref(tenantId, connector, externalId, "PERSON", personId, "Mã ở nguồn đã được ánh xạ tới cán bộ khác.");
  }

  @Override
  public void mapUnitXref(UUID tenantId, String connector, String externalId, UUID unitId) {
    putXref(tenantId, connector, externalId, "ORG_UNIT", unitId, "Mã đơn vị trên CSDL CBCCVC đã gắn với đơn vị khác.");
  }

  @Override
  public String requestCellTransfer(UUID tenantId, CellTransferRequest r) {
    require();
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("packageId", r.packageId());
    body.put("personId", r.personId());
    body.put("sourceTenantCode", r.sourceTenantCode());
    body.put("targetCell", r.targetCell());
    body.put("targetTenantId", r.targetTenantId());
    body.put("targetTenantCode", r.targetTenantCode());
    try {
      Map<?, ?> view = http.post().uri("/internal/v1/integ/cell-transfers").header(TENANT_HEADER, tenantId.toString())
          .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
      Object status = view == null ? null : view.get("status");
      return status == null ? "" : status.toString();
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().value() == 422 || e.getStatusCode() == HttpStatus.BAD_REQUEST
          || e.getStatusCode() == HttpStatus.CONFLICT) {
        throw new OrgConflictException("ORG-409-CELL-TRANSFER", "Cổng tích hợp từ chối yêu cầu chuyển hồ sơ liên cell.");
      }
      throw unavailable(e);
    } catch (RestClientException e) {
      throw unavailable(e);
    }
  }

  private void putXref(UUID tenantId, String connector, String externalId, String type, UUID internalId,
      String conflict) {
    require();
    try {
      http.put().uri("/internal/v1/integ/mapping-xref").header(TENANT_HEADER, tenantId.toString())
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("tenantId", tenantId, "connector", connector, "externalId", externalId,
              "internalType", type, "internalId", internalId))
          .retrieve().toBodilessEntity();
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode() == HttpStatus.CONFLICT) {
        throw new OrgConflictException("ORG-409-XREF-EXISTS", conflict);
      }
      throw unavailable(e);
    } catch (RestClientException e) {
      throw unavailable(e);
    }
  }

  private void post(UUID tenantId, String path, Map<String, Object> body) {
    require();
    try {
      http.post().uri(path).header(TENANT_HEADER, tenantId.toString()).contentType(MediaType.APPLICATION_JSON)
          .body(body).retrieve().toBodilessEntity();
    } catch (HttpClientErrorException e) {
      // Cổng từ chối (422: kết nối chưa cấu hình cho tenant hoặc không đồng bộ theo lần chạy): giữ lý do cổng trả
      // để danh sách lần đồng bộ hiện đúng nguyên nhân (MSG-01-14).
      ProblemDetail problem = null;
      try {
        problem = e.getResponseBodyAs(ProblemDetail.class);
      } catch (RuntimeException unreadable) {
        problem = null;
      }
      String detail = problem == null ? null : problem.getDetail();
      if (detail == null || detail.isBlank()) {
        throw unavailable(e);
      }
      throw new IntegrationUnavailableException("cổng tích hợp từ chối: " + detail, e);
    } catch (RestClientException e) {
      throw unavailable(e);
    }
  }

  private void require() {
    if (!configured) {
      throw new IntegrationUnavailableException("chưa cấu hình kết nối tới cổng tích hợp", null);
    }
  }

  private static IntegrationUnavailableException unavailable(Exception e) {
    return new IntegrationUnavailableException("cổng tích hợp không phản hồi", e);
  }
}
