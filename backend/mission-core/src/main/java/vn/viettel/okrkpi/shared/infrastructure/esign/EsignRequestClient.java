package vn.viettel.okrkpi.shared.infrastructure.esign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ErrorCatalog;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Client SVC-10 dùng chung cho các mô-đun nguồn chữ ký (appraisal, dossier...): mở yêu cầu ký bằng
 * {@code POST /api/v1/esign/requests} ({@code createEsignRequest} của {@code dossiers.yaml}) thay
 * mặt người ký, chuyển tiếp {@code Authorization} của người ký để SVC-10 kiểm step-up và người ký.
 * Đặt ở {@code shared} vì mô-đun nghiệp vụ không gọi HTTP trực tiếp (ArchUnit, TL-01).
 *
 * <p>Lỗi Problem có mã trong danh mục được ném lại thành {@link BusinessException}; 401 thành
 * {@link ResponseStatusException} 401; lỗi khác giữ nguyên để bên gọi đổi sang thông điệp nghiệp
 * vụ.
 */
public class EsignRequestClient {

  static final String PATH = "/api/v1/esign/requests";

  /** Header tenant đích của lời gọi dịch vụ tới dịch vụ (wiki trang 14 mục 42). */
  public static final String TENANT_HEADER = "X-Tenant-Id";

  private final RestClient http;
  private final ObjectMapper json;

  public EsignRequestClient(String baseUrl, ObjectMapper json) {
    this(RestClient.builder().baseUrl(Objects.requireNonNull(baseUrl, "baseUrl")).build(), json);
  }

  EsignRequestClient(RestClient http, ObjectMapper json) {
    this.http = Objects.requireNonNull(http, "http");
    this.json = Objects.requireNonNull(json, "json");
  }

  /**
   * @param purpose mục đích ký ({@code TU_DANH_GIA}, {@code BIEN_BAN}...)
   * @param provider nhà cung cấp, {@code null} dùng mặc định của SVC-10
   * @param authorization header {@code Authorization} của người ký, có thể {@code null}
   * @return mã yêu cầu ký
   */
  public UUID create(
      TenantId tenant,
      String purpose,
      String sourceModule,
      UUID sourceRefId,
      String provider,
      String idempotencyKey,
      String authorization) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("purpose", purpose);
    body.put("sourceModule", sourceModule);
    body.put("sourceRefId", sourceRefId.toString());
    if (provider != null) {
      body.put("provider", provider);
    }
    JsonNode created;
    try {
      created =
          http.post()
              .uri(PATH)
              .contentType(MediaType.APPLICATION_JSON)
              .headers(
                  h -> {
                    h.set("Idempotency-Key", idempotencyKey);
                    h.set(TENANT_HEADER, tenant.value().toString());
                    if (authorization != null) {
                      h.set(HttpHeaders.AUTHORIZATION, authorization);
                    }
                  })
              .body(body)
              .retrieve()
              .body(JsonNode.class);
    } catch (RestClientResponseException e) {
      throw relay(e);
    }
    if (created == null || !created.hasNonNull("id")) {
      throw new IllegalStateException("SVC-10 không trả mã yêu cầu ký");
    }
    return UUID.fromString(created.get("id").asText());
  }

  private RuntimeException relay(RestClientResponseException e) {
    if (e.getStatusCode().value() == 401) {
      return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần xác thực bổ sung để ký số.");
    }
    try {
      JsonNode problem = json.readTree(e.getResponseBodyAsByteArray());
      String code = problem == null ? null : problem.path("code").asText(null);
      if (code != null && ErrorCatalog.standard().contains(code)) {
        return new BusinessException(code, problem.path("detail").asText(null));
      }
    } catch (IOException | RuntimeException ignored) {
      // thân không phải Problem JSON
    }
    return e;
  }
}
