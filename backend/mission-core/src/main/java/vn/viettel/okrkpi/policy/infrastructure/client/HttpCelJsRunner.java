package vn.viettel.okrkpi.policy.infrastructure.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.policy.application.port.CelJsRunner;
import vn.viettel.okrkpi.shared.infrastructure.cel.CelJsClient;

/**
 * {@link CelJsRunner} qua {@link CelJsClient} (CORE-05-07). Số nguyên gửi dạng số không có phần
 * thập phân để bộ chạy giữ kiểu {@code int} của CEL.
 */
public class HttpCelJsRunner implements CelJsRunner {

  private final CelJsClient client;

  public HttpCelJsRunner(CelJsClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public List<Result> evaluate(List<Evaluation> evaluations) {
    return client.evaluate(evaluations).stream().map(HttpCelJsRunner::result).toList();
  }

  private static Result result(Map<String, Object> r) {
    return new Result(
        Boolean.TRUE.equals(r.get("ok")),
        r.get("value"),
        (String) r.get("errorCode"),
        (String) r.get("message"));
  }
}
