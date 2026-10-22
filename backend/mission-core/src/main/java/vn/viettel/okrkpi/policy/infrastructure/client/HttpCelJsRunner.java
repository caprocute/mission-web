package vn.viettel.okrkpi.policy.infrastructure.client;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.policy.application.port.CelJsRunner;

/**
 * Gọi bộ chạy cel-js ({@code backend/services/cel-js-runner}) qua {@code POST /v1/cel/evaluate}
 * (CORE-05-07). Thân yêu cầu {@code {"items": [Evaluation...]}}, trả {@code {"results":
 * [Result...]}} cùng thứ tự; số nguyên gửi dạng số không có phần thập phân để bộ chạy giữ kiểu
 * {@code int} của CEL.
 */
public class HttpCelJsRunner implements CelJsRunner {

  record Request(List<Evaluation> items) {}

  record Response(List<Result> results) {}

  private final RestClient http;

  public HttpCelJsRunner(RestClient.Builder builder, URI baseUrl, Duration timeout) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) Math.min(timeout.toMillis(), 5_000));
    factory.setReadTimeout((int) timeout.toMillis());
    this.http = builder.baseUrl(baseUrl.toString()).requestFactory(factory).build();
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public List<Result> evaluate(List<Evaluation> evaluations) {
    try {
      Response r =
          http.post()
              .uri("/v1/cel/evaluate")
              .contentType(MediaType.APPLICATION_JSON)
              .body(new Request(evaluations))
              .retrieve()
              .body(Response.class);
      if (r == null || r.results() == null || r.results().size() != evaluations.size()) {
        throw new IllegalStateException("Bộ chạy cel-js trả thiếu kết quả");
      }
      return r.results();
    } catch (org.springframework.web.client.RestClientException e) {
      throw new IllegalStateException("Không gọi được bộ chạy cel-js", e);
    }
  }
}
