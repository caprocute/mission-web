package vn.viettel.okrkpi.shared.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Client đọc gợi ý AI của SVC-14 ai-gateway ({@code GET /api/v1/ai/suggestions/{id}}, {@code
 * getAiSuggestion} của {@code platform.yaml}) thay mặt người dùng: chuyển tiếp {@code
 * Authorization} của người gọi để SVC-14 chỉ trả gợi ý của chính người đó (404 khi của người khác).
 * Đặt ở {@code shared} vì mô-đun nghiệp vụ không gọi HTTP trực tiếp (ArchUnit, TL-01); MOD-01 dùng
 * cho CORE-01-13.
 */
public class AiSuggestionClient {

  static final String PATH = "/api/v1/ai/suggestions/{id}";

  /** Header tenant đích của lời gọi dịch vụ tới dịch vụ (wiki trang 14 mục 42). */
  public static final String TENANT_HEADER = "X-Tenant-Id";

  private final RestClient http;

  public AiSuggestionClient(String baseUrl, Duration timeout) {
    this(build(baseUrl, timeout));
  }

  AiSuggestionClient(RestClient http) {
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

  /**
   * Thân {@code AiSuggestion}; rỗng khi SVC-14 trả 404 hoặc 403.
   *
   * @throws IllegalStateException khi SVC-14 lỗi, quá tải hoặc không gọi được
   */
  public Optional<JsonNode> find(TenantId tenant, UUID id, String authorization) {
    try {
      return Optional.ofNullable(
          http.get()
              .uri(PATH, id)
              .headers(
                  h -> {
                    h.set(TENANT_HEADER, tenant.value().toString());
                    if (authorization != null) {
                      h.set(HttpHeaders.AUTHORIZATION, authorization);
                    }
                  })
              .retrieve()
              .body(JsonNode.class));
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == 404 || status == 403) {
        return Optional.empty();
      }
      throw new IllegalStateException("SVC-14 trả lỗi " + status, e);
    } catch (RestClientException e) {
      throw new IllegalStateException("Không gọi được SVC-14", e);
    }
  }
}
