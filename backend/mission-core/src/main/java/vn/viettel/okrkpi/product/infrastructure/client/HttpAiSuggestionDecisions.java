package vn.viettel.okrkpi.product.infrastructure.client;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import vn.viettel.okrkpi.product.application.evidence.port.AiSuggestionDecisions;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException.Reason;

/**
 * {@link AiSuggestionDecisions} qua {@code POST /api/v1/ai/suggestions/{id}/decision} của SVC-14
 * ai-gateway ({@code decideAiSuggestion}), thời hạn mặc định 30 giây theo QT-07 bước 4. Không gửi
 * {@code editedFields}: hợp đồng chỉ nhận tên ô của thẻ nhiệm vụ, nên danh sách ô giữ, ô sửa của
 * minh chứng được ghi ở sổ cái (wiki trang 14).
 */
public class HttpAiSuggestionDecisions implements AiSuggestionDecisions {

  /** Tên phụ thuộc trong lỗi 503. */
  public static final String INSTANCE = "ai-gateway";

  static final String PATH = "/api/v1/ai/suggestions/{id}/decision";
  static final String TENANT_HEADER = "X-Tenant-Id";
  static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  static final Duration RETRY_AFTER = Duration.ofSeconds(5);

  private final RestClient http;

  public HttpAiSuggestionDecisions(String baseUrl, Duration timeout) {
    this(build(baseUrl, timeout));
  }

  HttpAiSuggestionDecisions(RestClient http) {
    this.http = Objects.requireNonNull(http, "http");
  }

  private static RestClient build(String baseUrl, Duration timeout) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) Math.min(timeout.toMillis(), 2_000));
    factory.setReadTimeout((int) timeout.toMillis());
    return RestClient.builder()
        .baseUrl(Objects.requireNonNull(baseUrl, "baseUrl"))
        .requestFactory(factory)
        .build();
  }

  @Override
  public void decide(
      TenantId tenant,
      UUID suggestionId,
      Decision decision,
      String targetRef,
      String authorization,
      String idempotencyKey) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decision", decision.name());
    body.put("targetRef", targetRef);
    try {
      http.post()
          .uri(PATH, suggestionId)
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .headers(
              h -> {
                h.set(TENANT_HEADER, tenant.value().toString());
                h.set(IDEMPOTENCY_KEY, idempotencyKey);
                if (authorization != null) {
                  h.set(HttpHeaders.AUTHORIZATION, authorization);
                }
              })
          .body(body)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == 409) {
        return;
      }
      if (status == 404 || status == 403) {
        throw new SuggestionNotFoundException("SVC-14 trả " + status + " cho gợi ý");
      }
      throw new DependencyUnavailableException(INSTANCE, Reason.IO_ERROR, RETRY_AFTER, e);
    } catch (ResourceAccessException e) {
      throw new DependencyUnavailableException(INSTANCE, Reason.TIMEOUT, RETRY_AFTER, e);
    } catch (RestClientException e) {
      throw new DependencyUnavailableException(INSTANCE, Reason.IO_ERROR, RETRY_AFTER, e);
    }
  }
}
