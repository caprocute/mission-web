package vn.viettel.okrkpi.org.infrastructure.integration;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.org.application.port.IntegrationGatewayPort;
import vn.viettel.okrkpi.org.application.port.IntegrationUnavailableException;
import vn.viettel.okrkpi.org.domain.OrgConflictException;

/**
 * Cổng nội bộ tới integration-gateway (SVC-13) qua mạng lưới dịch vụ (mTLS của Istio ambient, TL-04). Đường dẫn là
 * suy luận, chốt cùng DV-13-06 (wiki trang 14): {@code POST /internal/v1/integ/sync-runs},
 * {@code POST /internal/v1/integ/sync-runs/{id}/retry}, {@code PUT /internal/v1/integ/mapping-xref}.
 */
@Component
public class HttpIntegrationGatewayAdapter implements IntegrationGatewayPort {

  private final RestClient http;
  private final boolean configured;

  public HttpIntegrationGatewayAdapter(@Value("${okrkpi.org.integration-gateway.url:}") String baseUrl) {
    this.configured = baseUrl != null && !baseUrl.isBlank();
    this.http = RestClient.builder().baseUrl(configured ? baseUrl : "http://integration-gateway.invalid").build();
  }

  @Override
  public void requestRun(UUID tenantId, UUID runId, String connector) {
    post("/internal/v1/integ/sync-runs", Map.of("tenantId", tenantId, "runId", runId, "connector", connector));
  }

  @Override
  public void retryDeadLetters(UUID tenantId, UUID runId, UUID retryRunId, String connector) {
    post("/internal/v1/integ/sync-runs/" + runId + "/retry",
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

  private void putXref(UUID tenantId, String connector, String externalId, String type, UUID internalId,
      String conflict) {
    require();
    try {
      http.put().uri("/internal/v1/integ/mapping-xref").contentType(MediaType.APPLICATION_JSON)
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

  private void post(String path, Map<String, Object> body) {
    require();
    try {
      http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
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
