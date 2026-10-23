package vn.viettel.okrkpi.policy.infrastructure.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.policy.application.port.WorkflowBindings;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * {@link WorkflowBindings} qua API nội bộ của SVC-06 (CORE-05-11). DV-06-08 chưa công bố đường dẫn
 * nên các đường dẫn dưới {@code /internal/v1/workflow} là suy luận, theo tiền lệ {@code
 * /internal/v1/...} của các API nội bộ khác (wiki trang 14). Token dịch vụ client-credentials của
 * mission-core; tenant đích gửi trong {@code X-Tenant-Id} vì token dịch vụ không mang claim tenant.
 * Resilience4j instance {@link #INSTANCE} dùng cấu hình chuẩn: timeout 3 s, circuit breaker, retry
 * cho GET và lệnh có {@code Idempotency-Key}.
 */
public class WorkflowBindingClient implements WorkflowBindings {

  /** Tên instance Resilience4j và tên phụ thuộc trong lỗi 503. */
  public static final String INSTANCE = "workflow-service";

  static final String TENANT_HEADER = "X-Tenant-Id";
  static final String BINDINGS = "/internal/v1/workflow/bindings";
  static final String TEMPLATES = "/internal/v1/workflow/templates";
  static final Duration RETRY_AFTER = Duration.ofSeconds(5);
  static final String WF_PARAMS = "POLICY-422-WF-PARAMS";

  /** Mã 422 của hợp đồng {@code saveWorkflowBinding} được giữ nguyên khi SVC-06 trả về. */
  private static final Set<String> KEPT_422 =
      Set.of(WF_PARAMS, "POLICY-422-CEL-TYPE", "POLICY-422-TEMPLATE-NOT-ACTIVE");

  private final ResilientClient http;
  private final Supplier<String> token;

  public WorkflowBindingClient(ResilientClient http, Supplier<String> token) {
    this.http = Objects.requireNonNull(http, "http");
    this.token = Objects.requireNonNull(token, "token");
  }

  @Override
  public List<Map<String, Object>> list(TenantId tenant, UUID packVersionId) {
    Map<?, ?> page =
        call(
            () ->
                http.exchange(
                    HttpMethod.GET,
                    BINDINGS + "?policyPackVersionId={v}",
                    null,
                    headers(tenant),
                    Map.class,
                    packVersionId));
    List<Map<String, Object>> items = new ArrayList<>();
    if (page != null && page.get("items") instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m) {
          items.add(document(m));
        }
      }
    }
    return items;
  }

  @Override
  public Optional<Versioned> find(TenantId tenant, UUID packVersionId, String processKind) {
    try {
      Map<?, ?> body =
          call(
              () ->
                  http.exchange(
                      HttpMethod.GET,
                      BINDINGS + "/{kind}?policyPackVersionId={v}",
                      null,
                      headers(tenant),
                      Map.class,
                      processKind,
                      packVersionId));
      return Optional.of(versioned(body));
    } catch (NotFound e) {
      return Optional.empty();
    }
  }

  @Override
  public Versioned save(
      TenantId tenant,
      UUID packVersionId,
      String processKind,
      long expectedRowVersion,
      String idempotencyKey,
      Map<String, Object> binding) {
    HttpHeaders h = headers(tenant);
    h.setContentType(MediaType.APPLICATION_JSON);
    h.setIfMatch("\"" + expectedRowVersion + "\"");
    h.set(ResilientClient.IDEMPOTENCY_KEY, idempotencyKey);
    try {
      Map<?, ?> body =
          call(
              () ->
                  http.exchange(
                      HttpMethod.PUT,
                      BINDINGS + "/{kind}?policyPackVersionId={v}",
                      binding,
                      h,
                      Map.class,
                      processKind,
                      packVersionId));
      return versioned(body);
    } catch (NotFound e) {
      throw new BusinessException(
          "POLICY-422-TEMPLATE-NOT-ACTIVE",
          "Mẫu luồng đã chọn không có hoặc chưa được phát hành",
          List.of(
              new ProblemFieldError("templateKey", "Mẫu luồng chưa được phát hành", "BR-15-11")));
    } catch (Conflict e) {
      throw new VersionConflictException("POLICY", expectedRowVersion, null);
    }
  }

  @Override
  public Map<String, Object> simulate(
      TenantId tenant, Map<String, Object> request, String idempotencyKey) {
    HttpHeaders h = headers(tenant);
    h.setContentType(MediaType.APPLICATION_JSON);
    h.set(ResilientClient.IDEMPOTENCY_KEY, idempotencyKey);
    try {
      return document(
          call(
              () -> http.exchange(HttpMethod.POST, BINDINGS + "/simulate", request, h, Map.class)));
    } catch (NotFound | Conflict e) {
      throw new BusinessException(
          WF_PARAMS,
          "Không mô phỏng được với mẫu luồng và tham số đã chọn",
          List.of(),
          "MSG-15-09");
    }
  }

  @Override
  public Map<String, Object> templates(
      TenantId tenant, String processKind, String q, int limit, String cursor) {
    StringBuilder uri = new StringBuilder(TEMPLATES).append("?status=HIEU_LUC&limit={limit}");
    List<Object> vars = new ArrayList<>(List.of(limit));
    if (processKind != null) {
      uri.append("&processKind={processKind}");
      vars.add(processKind);
    }
    if (q != null) {
      uri.append("&q={q}");
      vars.add(q);
    }
    if (cursor != null) {
      uri.append("&cursor={cursor}");
      vars.add(cursor);
    }
    return document(
        call(
            () ->
                http.exchange(
                    HttpMethod.GET,
                    uri.toString(),
                    null,
                    headers(tenant),
                    Map.class,
                    vars.toArray())));
  }

  @Override
  public Optional<Versioned> template(TenantId tenant, String templateKey, int versionNo) {
    try {
      Map<?, ?> body =
          call(
              () ->
                  http.exchange(
                      HttpMethod.GET,
                      TEMPLATES + "/{key}/versions/{v}",
                      null,
                      headers(tenant),
                      Map.class,
                      templateKey,
                      versionNo));
      return Optional.of(versioned(body));
    } catch (NotFound e) {
      return Optional.empty();
    }
  }

  private HttpHeaders headers(TenantId tenant) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token.get());
    h.set(TENANT_HEADER, tenant.value().toString());
    h.setAccept(List.of(MediaType.APPLICATION_JSON));
    return h;
  }

  /** Chạy lời gọi và chuyển lỗi HTTP của SVC-06 sang lỗi của hợp đồng. */
  private static Map<?, ?> call(Supplier<Map<?, ?>> work) {
    try {
      Map<?, ?> body = work.get();
      if (body == null) {
        throw unavailable(new RestClientException("workflow-service trả thân rỗng"));
      }
      return body;
    } catch (HttpClientErrorException e) {
      HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
      if (status == HttpStatus.NOT_FOUND) {
        throw new NotFound();
      }
      if (status == HttpStatus.CONFLICT || status == HttpStatus.PRECONDITION_FAILED) {
        throw new Conflict();
      }
      if (status == HttpStatus.UNPROCESSABLE_ENTITY || status == HttpStatus.BAD_REQUEST) {
        throw invalid(e);
      }
      // 401, 403, 429 và các mã khác là lỗi cấu hình hoặc quá tải giữa hai dịch vụ.
      throw unavailable(e);
    } catch (RestClientException | IllegalArgumentException e) {
      throw unavailable(e);
    }
  }

  /**
   * 422 của SVC-06: giữ mã của hợp đồng nếu có, còn lại là {@code POLICY-422-WF-PARAMS}; mỗi vi
   * phạm linter ({@code issues[]} dạng {@code WorkflowLintResult}) hoặc lỗi trường ({@code
   * errors[]}) thành một phần tử {@code errors[]} giữ mã quy tắc (MSG-15-09).
   */
  static BusinessException invalid(HttpClientErrorException e) {
    Map<?, ?> problem = null;
    try {
      problem = e.getResponseBodyAs(Map.class);
    } catch (RuntimeException ignored) {
      // Thân không phải JSON.
    }
    String code = problem == null ? null : string(problem.get("code"));
    String detail = problem == null ? null : string(problem.get("detail"));
    List<ProblemFieldError> errors = new ArrayList<>();
    if (problem != null) {
      collect(problem.get("errors"), errors);
      collect(problem.get("issues"), errors);
      if (problem.get("lint") instanceof Map<?, ?> lint) {
        collect(lint.get("issues"), errors);
      }
    }
    return new BusinessException(
        code != null && KEPT_422.contains(code) ? code : WF_PARAMS,
        detail == null ? "Tham số luồng duyệt chưa hợp lệ" : detail,
        errors,
        "MSG-15-09");
  }

  private static void collect(Object source, List<ProblemFieldError> errors) {
    if (!(source instanceof List<?> list)) {
      return;
    }
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> m)) {
        continue;
      }
      String field = string(m.get("field"));
      if (field == null) {
        field = string(m.get("elementId"));
      }
      String message = string(m.get("message"));
      errors.add(
          new ProblemFieldError(
              field == null ? "params" : field,
              message == null ? "Tham số chưa hợp lệ" : message,
              string(m.get("rule"))));
    }
  }

  private static Versioned versioned(Map<?, ?> body) {
    Map<String, Object> doc = document(body);
    long rowVersion = doc.get("rowVersion") instanceof Number n ? n.longValue() : 0L;
    return new Versioned(doc, rowVersion);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> document(Map<?, ?> body) {
    return (Map<String, Object>) body;
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }

  private static DependencyUnavailableException unavailable(Exception e) {
    return new DependencyUnavailableException(
        INSTANCE, DependencyUnavailableException.Reason.IO_ERROR, RETRY_AFTER, e);
  }

  /** 404 của SVC-06, xử lý theo từng thao tác. */
  private static final class NotFound extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotFound() {
      super(null, null, false, false);
    }
  }

  /** 409 hoặc 412 của SVC-06. */
  private static final class Conflict extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Conflict() {
      super(null, null, false, false);
    }
  }
}
