package vn.viettel.okrkpi.shared.infrastructure.cel;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client bộ chạy cel-js ({@code backend/services/cel-js-runner}, {@code POST /v1/cel/evaluate}) mà
 * MOD-05 dùng để đối chiếu cel-java với cel-js khi chạy kiểm thử Policy Pack (CORE-05-07). Thân yêu
 * cầu {@code {"items": [...]}}, trả {@code {"results": [...]}} cùng thứ tự. Các mô-đun nghiệp vụ
 * không gọi HTTP trực tiếp (ArchUnit), nên client nằm ở {@code shared.infrastructure} như client
 * Keycloak và saga.
 */
public class CelJsClient {

  record Request(List<?> items) {}

  record Response(List<Map<String, Object>> results) {}

  private final RestClient http;

  public CelJsClient(URI baseUrl, Duration timeout) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) Math.min(timeout.toMillis(), 5_000));
    factory.setReadTimeout((int) timeout.toMillis());
    this.http = RestClient.builder().baseUrl(baseUrl.toString()).requestFactory(factory).build();
  }

  /** Gửi một lô biểu thức; ném {@link IllegalStateException} khi không gọi được hoặc trả thiếu. */
  public List<Map<String, Object>> evaluate(List<?> items) {
    try {
      Response r =
          http.post()
              .uri("/v1/cel/evaluate")
              .contentType(MediaType.APPLICATION_JSON)
              .body(new Request(items))
              .retrieve()
              .body(Response.class);
      if (r == null || r.results() == null || r.results().size() != items.size()) {
        throw new IllegalStateException("Bộ chạy cel-js trả thiếu kết quả");
      }
      return r.results();
    } catch (RestClientException e) {
      throw new IllegalStateException("Không gọi được bộ chạy cel-js", e);
    }
  }
}
