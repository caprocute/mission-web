package vn.viettel.okrkpi.shared.infrastructure.workflow;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/**
 * Client cầu nối luồng duyệt của SVC-06 ({@code POST /internal/v1/workflow/approvals} và {@code
 * /approvals/cancel}, CORE-02-08). Khởi tạo idempotent theo (tenant, mẫu, khoá nghiệp vụ, phiên bản
 * đối tượng) nên bên gọi thử lại an toàn. Lỗi mạng, 5xx, 401, 429 ném {@link
 * DependencyUnavailableException}; 4xx còn lại là yêu cầu sai, ném {@link Rejected} để không thử
 * lại vô hạn.
 */
public class WorkflowApprovalClient {

  /** SVC-06 từ chối yêu cầu (400, 403, 404, 422): dữ liệu gửi sai, thử lại không có ích. */
  public static final class Rejected extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Rejected(HttpClientErrorException cause) {
      super("workflow-service từ chối: " + cause.getStatusCode().value(), cause);
    }
  }

  /** Kết quả khởi tạo: mã tiến trình và cờ vừa tạo mới. */
  public record Started(String processInstanceId, boolean created) {}

  private static final ParameterizedTypeReference<Map<String, Object>> MAP =
      new ParameterizedTypeReference<>() {};

  private final RestClient http;
  private final Supplier<String> token;

  public WorkflowApprovalClient(
      RestClient.Builder builder, String baseUrl, Supplier<String> token) {
    this.http = builder.baseUrl(Objects.requireNonNull(baseUrl, "baseUrl")).build();
    this.token = Objects.requireNonNull(token, "token");
  }

  /** Khởi tạo luồng duyệt theo thân yêu cầu của API nội bộ. */
  public Started start(TenantId tenantId, Map<String, Object> body) {
    Map<String, Object> out = post(tenantId, "/internal/v1/workflow/approvals", body);
    if (!(out.get("processInstanceId") instanceof String pid)) {
      throw unavailable(new RestClientException("workflow-service không trả mã tiến trình"));
    }
    return new Started(pid, Boolean.TRUE.equals(out.get("created")));
  }

  /** Kết thúc tiến trình còn chờ người duyệt của đối tượng; trả số tiến trình đã kết thúc. */
  public int cancel(TenantId tenantId, String template, String businessId, String reason) {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("template", template);
    body.put("businessId", businessId);
    body.put("reason", reason);
    Map<String, Object> out = post(tenantId, "/internal/v1/workflow/approvals/cancel", body);
    return out.get("cancelled") instanceof Number n ? n.intValue() : 0;
  }

  private Map<String, Object> post(TenantId tenantId, String path, Map<String, Object> body) {
    Objects.requireNonNull(tenantId, "tenantId");
    try {
      Map<String, Object> out =
          http.post()
              .uri(path)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.get())
              .header(WorkflowSagaClient.TENANT_HEADER, tenantId.value().toString())
              .body(body)
              .retrieve()
              .body(MAP);
      return out == null ? Map.of() : out;
    } catch (HttpClientErrorException e) {
      int status = e.getStatusCode().value();
      if (status == 401 || status == 408 || status == 429) {
        throw unavailable(e);
      }
      throw new Rejected(e);
    } catch (RestClientException e) {
      throw unavailable(e);
    }
  }

  private static DependencyUnavailableException unavailable(Exception e) {
    return new DependencyUnavailableException(
        "workflow-service",
        DependencyUnavailableException.Reason.IO_ERROR,
        Duration.ofSeconds(5),
        e);
  }
}
