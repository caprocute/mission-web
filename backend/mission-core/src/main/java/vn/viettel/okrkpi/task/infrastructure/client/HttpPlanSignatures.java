package vn.viettel.okrkpi.task.infrastructure.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;
import vn.viettel.okrkpi.task.application.plan.port.PlanSignatures;

/**
 * Tạo yêu cầu ký trên SVC-10 bằng {@code POST /api/v1/esign/requests} (hợp đồng {@code
 * createEsignRequest}) với nguồn {@code task}/{@code <mã phiên bản>}; SVC-10 hỏi lại cổng nguồn ký
 * của MOD-02 để lấy băm đã khoá và người được ký. Token của người duyệt được chuyển tiếp vì SVC-10
 * kiểm người gọi là người ký và phiên AAL2 (suy luận, wiki trang 14). Hợp đồng chưa có mốc pháp lý
 * riêng cho kế hoạch nên {@code purpose} là cấu hình.
 */
public class HttpPlanSignatures implements PlanSignatures {

  static final String PATH = "/api/v1/esign/requests";
  static final String SOURCE_MODULE = "task";

  /** {@code signMethod} của hợp đồng kế hoạch → {@code EsignProvider}; còn lại chưa hỗ trợ. */
  private static final Map<String, String> PROVIDERS = Map.of("MYSIGN", "MYSIGN");

  private final ResilientClient esign;
  private final String purpose;

  public HttpPlanSignatures(ResilientClient esign, String purpose) {
    this.esign = Objects.requireNonNull(esign, "esign");
    this.purpose = Objects.requireNonNull(purpose, "purpose");
  }

  @Override
  public UUID request(Request request) {
    String provider = PROVIDERS.get(request.signMethod());
    if (provider == null) {
      throw new BusinessException(
          "COMMON-422-VALIDATION",
          "Phương thức ký số này chưa được hỗ trợ",
          List.of(new ProblemFieldError("signMethod", "Phương thức ký số chưa được hỗ trợ", null)));
    }
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(request.callerToken());
    headers.set(
        ResilientClient.IDEMPOTENCY_KEY,
        "ke-hoach:"
            + request.versionId()
            + ":"
            + (request.idempotencyKey() == null ? request.approver() : request.idempotencyKey()));
    Map<String, Object> body =
        Map.of(
            "purpose", purpose,
            "sourceModule", SOURCE_MODULE,
            "sourceRefId", request.versionId().toString(),
            "provider", provider);
    Map<?, ?> created;
    try {
      created = esign.exchange(HttpMethod.POST, PATH, body, headers, Map.class);
    } catch (HttpClientErrorException e) {
      throw problem(e);
    }
    Object id = created == null ? null : created.get("id");
    if (id == null) {
      throw new IllegalStateException("Dịch vụ ký số không trả mã yêu cầu ký");
    }
    return UUID.fromString(id.toString());
  }

  /** Lỗi 4xx của SVC-10 giữ nguyên mã lỗi (ví dụ {@code ESIGN-409-REQUEST-PENDING}). */
  private static BusinessException problem(HttpClientErrorException e) {
    String code = null;
    String detail = null;
    try {
      Map<?, ?> body = e.getResponseBodyAs(Map.class);
      if (body != null) {
        code = body.get("code") == null ? null : body.get("code").toString();
        detail = body.get("detail") == null ? null : body.get("detail").toString();
      }
    } catch (RuntimeException ignored) {
      // Thân không phải JSON.
    }
    String message = detail == null ? "Không tạo được yêu cầu ký số" : detail;
    if (code != null) {
      try {
        return new BusinessException(code, message);
      } catch (IllegalArgumentException unknown) {
        // Mã ngoài danh mục của hợp đồng: trả lỗi kiểm tra chung.
      }
    }
    return new BusinessException("COMMON-422-VALIDATION", message);
  }
}
