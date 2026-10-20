package vn.viettel.okrkpi.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Dựng {@link ProblemDetail} đúng lược đồ {@code ProblemDetails} của {@code common.yaml}: {@code
 * type}, {@code title}, {@code status}, {@code detail}, {@code instance} và phần mở rộng {@code
 * code}, {@code msg}, {@code errors[]}, {@code traceId}.
 */
public class ProblemFactory {

  /** Tên các trường mở rộng của hợp đồng. */
  public static final String CODE = "code";

  public static final String MSG = "msg";
  public static final String ERRORS = "errors";
  public static final String TRACE_ID = "traceId";

  private final TraceIdResolver traceIds;
  private final ErrorCatalog catalog;

  public ProblemFactory(TraceIdResolver traceIds, ErrorCatalog catalog) {
    this.traceIds = Objects.requireNonNull(traceIds, "traceIds");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  public ErrorCatalog catalog() {
    return catalog;
  }

  /** Mã vết của yêu cầu (cùng giá trị với {@code traceId} của Problem). */
  public String traceId(HttpServletRequest request) {
    return traceIds.resolve(request);
  }

  /** Problem của một mã trong danh mục. */
  public ProblemDetail fromCode(
      ErrorCode code,
      String detail,
      List<ProblemFieldError> errors,
      String msg,
      HttpServletRequest request) {
    return finish(base(code, detail, msg), errors, request, requestPath(request));
  }

  private static ProblemDetail base(ErrorCode code, String detail, String msg) {
    ProblemDetail pd = ProblemDetail.forStatus(code.status());
    pd.setType(URI.create(code.type()));
    pd.setTitle(code.title());
    pd.setDetail(detail);
    pd.setProperty(CODE, code.code());
    String effectiveMsg = msg != null ? msg : code.msg();
    if (effectiveMsg != null) {
      pd.setProperty(MSG, effectiveMsg);
    }
    return pd;
  }

  /**
   * Problem chuẩn của một HTTP status (lỗi khung, không có mã nghiệp vụ). Status 403 mang mã {@code
   * COMMON-403-OUT-OF-SCOPE} của hợp đồng.
   *
   * @param detail mô tả tiếng Việt; {@code null} dùng mô tả chung của status
   */
  public ProblemDetail standard(
      int status, String detail, List<ProblemFieldError> errors, HttpServletRequest request) {
    return standard(status, detail, errors, request, requestPath(request));
  }

  /**
   * Như {@link #standard(int, String, List, HttpServletRequest)} với {@code instance} cho trước.
   */
  public ProblemDetail standard(
      int status,
      String detail,
      List<ProblemFieldError> errors,
      HttpServletRequest request,
      String instance) {
    if (status == 403) {
      ErrorCode forbidden = catalog.require(ProblemTypes.FORBIDDEN_CODE);
      String d = detail != null ? detail : ProblemTypes.forStatus(403).detail();
      return finish(base(forbidden, d, null), errors, request, instance);
    }
    ProblemTypes.Standard std = ProblemTypes.forStatus(status);
    ProblemDetail pd = ProblemDetail.forStatus(status);
    pd.setType(URI.create(std.type()));
    pd.setTitle(std.title());
    pd.setDetail(detail != null ? detail : std.detail());
    return finish(pd, errors, request, instance);
  }

  /** Đóng gói thành phản hồi {@code application/problem+json}. */
  public static ResponseEntity<ProblemDetail> toResponse(ProblemDetail pd, HttpHeaders headers) {
    HttpHeaders h = new HttpHeaders();
    if (headers != null) {
      h.putAll(headers);
    }
    h.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    return new ResponseEntity<>(pd, h, HttpStatusCode.valueOf(pd.getStatus()));
  }

  private ProblemDetail finish(
      ProblemDetail pd, List<ProblemFieldError> errors, HttpServletRequest request, String path) {
    if (path != null && !path.isEmpty()) {
      pd.setInstance(URI.create(path));
    }
    if (errors != null && !errors.isEmpty()) {
      pd.setProperty(ERRORS, List.copyOf(errors));
    }
    pd.setProperty(TRACE_ID, traceIds.resolve(request));
    return pd;
  }

  private static String requestPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return uri == null ? null : safePath(uri);
  }

  /** Chỉ giữ đường dẫn hợp lệ làm URI tương đối; đường dẫn lạ bỏ qua thay vì làm hỏng phản hồi. */
  static String safePath(String path) {
    try {
      return URI.create(path).getRawPath();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
