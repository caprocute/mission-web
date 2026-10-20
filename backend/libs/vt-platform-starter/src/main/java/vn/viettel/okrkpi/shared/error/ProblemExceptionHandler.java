package vn.viettel.okrkpi.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.WebUtils;

/**
 * Bộ xử lý lỗi chung (ADR-24; TL-01 "Định dạng lỗi chuẩn RFC 9457"): mọi lỗi của controller trả
 * {@code application/problem+json} đúng lược đồ {@code ProblemDetails} của {@code common.yaml}.
 *
 * <ul>
 *   <li>{@link BusinessException} → status, {@code type}, {@code title}, {@code code}, {@code msg}
 *       của mã trong danh mục;
 *   <li>lỗi Jakarta Validation ở thân yêu cầu → 422 {@code COMMON-422-VALIDATION} kèm {@code
 *       errors[]}; ở tham số đường dẫn, truy vấn, header → 400 kèm {@code errors[]};
 *   <li>{@link OptimisticLockingFailureException}, {@link VersionConflictException} → 409 {@code
 *       <MODULE>-409-VERSION-CONFLICT}; thiếu {@code If-Match} → 428 (BS-02 HD-01);
 *   <li>lỗi khung của Spring MVC → status của Spring, {@code title}/{@code detail} tiếng Việt chung
 *       (bỏ {@code detail} gốc của Spring vì có thể chứa tên lớp Java);
 *   <li>ngoại lệ lạ → 500 chung; chi tiết chỉ ghi nhật ký kèm {@code traceId}.
 * </ul>
 *
 * <p>Thứ tự thấp nhất để {@code @RestControllerAdvice} riêng của service (nếu có) được xét trước.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProblemExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);
  private static final String IF_MATCH = "If-Match";

  private final ProblemFactory problems;
  private final ErrorModuleResolver modules;

  public ProblemExceptionHandler(ProblemFactory problems, ErrorModuleResolver modules) {
    this.problems = problems;
    this.modules = modules;
  }

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ProblemDetail> handleBusiness(
      BusinessException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problems.fromCode(ex.errorCode(), ex.detail(), ex.errors(), ex.msg(), request);
    logClientError(pd, request);
    return ProblemFactory.toResponse(pd, null);
  }

  @ExceptionHandler(VersionConflictException.class)
  public ResponseEntity<ProblemDetail> handleVersionConflict(
      VersionConflictException ex, HttpServletRequest request) {
    String module = ex.module() != null ? ex.module() : modules.resolve(request);
    return versionConflict(module, ex.detail(), request);
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ProblemDetail> handleOptimisticLocking(
      OptimisticLockingFailureException ex, HttpServletRequest request) {
    // Thông điệp gốc của Spring/Hibernate có tên thực thể và câu SQL: không đưa ra phản hồi.
    return versionConflict(
        modules.resolve(request), VersionConflictException.DEFAULT_DETAIL, request);
  }

  @ExceptionHandler(PreconditionRequiredException.class)
  public ResponseEntity<ProblemDetail> handlePreconditionRequired(
      PreconditionRequiredException ex, HttpServletRequest request) {
    ProblemDetail pd = problems.standard(428, null, List.of(), request);
    logClientError(pd, request);
    return ProblemFactory.toResponse(pd, null);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ProblemDetail> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    List<ProblemFieldError> errors =
        ex.getConstraintViolations() == null
            ? List.of()
            : ValidationErrors.fromViolations(ex.getConstraintViolations());
    ProblemDetail pd = validationProblem(errors, request);
    logClientError(pd, request);
    return ProblemFactory.toResponse(pd, null);
  }

  /** Ngoại lệ không lường trước: 500 chung, chi tiết chỉ vào nhật ký (TL-01, BACKEND.md mục 3). */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
    ProblemDetail pd = problems.standard(500, null, List.of(), request);
    log.error(
        "Lỗi ngoài dự kiến traceId={} instance={}",
        pd.getProperties().get(ProblemFactory.TRACE_ID),
        pd.getInstance(),
        ex);
    request.setAttribute(WebUtils.ERROR_EXCEPTION_ATTRIBUTE, ex);
    return ProblemFactory.toResponse(pd, null);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    HttpServletRequest req = servletRequest(request);
    MethodParameter parameter = ex.getParameter();
    boolean body = parameter.hasParameterAnnotation(RequestBody.class);
    List<ProblemFieldError> errors =
        ValidationErrors.fromBindingResult(
            ex.getBindingResult(), body ? null : parameterName(parameter));
    ProblemDetail pd =
        body ? validationProblem(errors, req) : problems.standard(400, null, errors, req);
    return handleExceptionInternal(
        ex, pd, headers, HttpStatusCode.valueOf(pd.getStatus()), request);
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    HttpServletRequest req = servletRequest(request);
    if (ex.isForReturnValue()) {
      return handleExceptionInternal(ex, null, headers, HttpStatusCode.valueOf(500), request);
    }
    boolean body = false;
    List<ProblemFieldError> errors = new ArrayList<>();
    for (ParameterValidationResult result : ex.getParameterValidationResults()) {
      MethodParameter parameter = result.getMethodParameter();
      boolean isBody = parameter.hasParameterAnnotation(RequestBody.class);
      body |= isBody;
      String name = isBody ? null : parameterName(parameter);
      if (result.getContainerIndex() != null && name != null) {
        name = name + "[" + result.getContainerIndex() + "]";
      }
      if (result instanceof ParameterErrors pe) {
        errors.addAll(ValidationErrors.fromBindingResult(pe, name));
        continue;
      }
      for (MessageSourceResolvable error : result.getResolvableErrors()) {
        errors.add(ProblemFieldError.of(name == null ? "" : name, message(result, error)));
      }
    }
    ProblemDetail pd =
        body ? validationProblem(errors, req) : problems.standard(400, null, errors, req);
    return handleExceptionInternal(
        ex, pd, headers, HttpStatusCode.valueOf(pd.getStatus()), request);
  }

  @Override
  protected ResponseEntity<Object> handleServletRequestBindingException(
      ServletRequestBindingException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    if (ex instanceof MissingRequestHeaderException missing
        && IF_MATCH.equalsIgnoreCase(missing.getHeaderName())) {
      ProblemDetail pd = problems.standard(428, null, List.of(), servletRequest(request));
      return handleExceptionInternal(ex, pd, headers, HttpStatusCode.valueOf(428), request);
    }
    return super.handleServletRequestBindingException(ex, headers, status, request);
  }

  /**
   * Mọi lỗi khung của Spring MVC đi qua đây: thay thân mặc định của Spring (tiếng Anh, có thể chứa
   * tên lớp) bằng Problem chuẩn của status; giữ header của Spring (ví dụ {@code Allow} khi 405).
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    HttpServletRequest req = servletRequest(request);
    ProblemDetail pd =
        body instanceof ProblemDetail p && isNormalized(p)
            ? p
            : problems.standard(statusCode.value(), null, List.of(), req);
    if (statusCode.is5xxServerError()) {
      log.error(
          "Lỗi máy chủ status={} traceId={} instance={}",
          statusCode.value(),
          pd.getProperties().get(ProblemFactory.TRACE_ID),
          pd.getInstance(),
          ex);
    } else {
      logClientError(pd, req);
    }
    HttpHeaders h = new HttpHeaders();
    if (headers != null) {
      h.putAll(headers);
    }
    h.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    return super.handleExceptionInternal(ex, pd, h, statusCode, request);
  }

  private ResponseEntity<ProblemDetail> versionConflict(
      String module, String detail, HttpServletRequest request) {
    ErrorCode code = problems.catalog().versionConflict(module);
    ProblemDetail pd = problems.fromCode(code, detail, List.of(), null, request);
    logClientError(pd, request);
    return ProblemFactory.toResponse(pd, null);
  }

  private ProblemDetail validationProblem(
      List<ProblemFieldError> errors, HttpServletRequest request) {
    ErrorCode code = problems.catalog().require(ProblemTypes.VALIDATION_CODE);
    return problems.fromCode(code, null, errors, null, request);
  }

  private static String message(ParameterValidationResult result, MessageSourceResolvable error) {
    if (error instanceof ObjectError oe) {
      return ValidationErrors.message(oe);
    }
    ConstraintViolation<?> cv;
    try {
      cv = result.unwrap(error, ConstraintViolation.class);
    } catch (IllegalArgumentException e) {
      cv = null;
    }
    if (cv != null) {
      return ValidationErrors.message(ValidationErrors.constraintName(cv), cv);
    }
    return ValidationErrors.nonConstraintMessage(error);
  }

  /**
   * Tên tham số như client gửi: tên trong annotation, hoặc tên tham số Java (biên dịch
   * -parameters).
   */
  static String parameterName(MethodParameter parameter) {
    RequestParam rp = parameter.getParameterAnnotation(RequestParam.class);
    if (rp != null) {
      return firstNonEmpty(rp.name(), rp.value(), parameter.getParameterName());
    }
    PathVariable pv = parameter.getParameterAnnotation(PathVariable.class);
    if (pv != null) {
      return firstNonEmpty(pv.name(), pv.value(), parameter.getParameterName());
    }
    RequestHeader rh = parameter.getParameterAnnotation(RequestHeader.class);
    if (rh != null) {
      return firstNonEmpty(rh.name(), rh.value(), parameter.getParameterName());
    }
    return firstNonEmpty(parameter.getParameterName(), "", "");
  }

  private static String firstNonEmpty(String a, String b, String c) {
    if (a != null && !a.isEmpty()) {
      return a;
    }
    if (b != null && !b.isEmpty()) {
      return b;
    }
    return c == null ? "" : c;
  }

  private static boolean isNormalized(ProblemDetail pd) {
    return pd.getProperties() != null && pd.getProperties().containsKey(ProblemFactory.TRACE_ID);
  }

  private static HttpServletRequest servletRequest(WebRequest request) {
    if (request instanceof NativeWebRequest nw) {
      HttpServletRequest r = nw.getNativeRequest(HttpServletRequest.class);
      if (r != null) {
        return r;
      }
    }
    throw new IllegalStateException("Bộ xử lý lỗi chỉ dùng cho ứng dụng servlet");
  }

  private static void logClientError(ProblemDetail pd, HttpServletRequest request) {
    if (log.isDebugEnabled()) {
      Object code = pd.getProperties() == null ? null : pd.getProperties().get(ProblemFactory.CODE);
      log.debug(
          "Trả lỗi status={} code={} traceId={} method={}",
          pd.getStatus(),
          code,
          pd.getProperties() == null ? null : pd.getProperties().get(ProblemFactory.TRACE_ID),
          request.getMethod().toUpperCase(Locale.ROOT));
    }
  }
}
