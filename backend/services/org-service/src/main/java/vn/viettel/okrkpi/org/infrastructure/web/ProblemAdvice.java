package vn.viettel.okrkpi.org.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import vn.viettel.okrkpi.org.application.ForbiddenOperationException;
import vn.viettel.okrkpi.org.application.IdempotencyMismatchException;
import vn.viettel.okrkpi.org.application.StepUpRequiredException;
import vn.viettel.okrkpi.org.domain.OrgConflictException;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;
import vn.viettel.okrkpi.org.domain.VersionConflictException;

/**
 * Lỗi theo RFC 9457 ({@code application/problem+json}) với phần mở rộng TL-01: {@code code}
 * {@code <MODULE>-<HTTP>-<TÊN>}, {@code msg} (mã TL-02), {@code errors[]}, {@code traceId}. Không lộ stack trace hay
 * câu SQL.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProblemAdvice {

  private static final Logger LOG = LoggerFactory.getLogger(ProblemAdvice.class);
  private static final String BASE = "https://okrkpi.vn/problems/";

  @ExceptionHandler(OrgRuleViolation.class)
  ResponseEntity<ProblemDetail> rule(OrgRuleViolation e, HttpServletRequest req) {
    ProblemDetail p = problem(HttpStatus.UNPROCESSABLE_ENTITY, "business-rule", "Dữ liệu chưa hợp lệ", e.getMessage(),
        e.code(), req);
    if (e.msg() != null) {
      p.setProperty("msg", e.msg());
    }
    if (e.field() != null) {
      p.setProperty("errors", List.of(fieldError(e.field(), e.getMessage(), e.rule())));
    }
    return send(p);
  }

  @ExceptionHandler(IdempotencyMismatchException.class)
  ResponseEntity<ProblemDetail> idempotency(IdempotencyMismatchException e, HttpServletRequest req) {
    return send(problem(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-mismatch", "Yêu cầu trùng khoá chống ghi lặp",
        e.getMessage(), "COMMON-422-IDEMPOTENCY-MISMATCH", req));
  }

  @ExceptionHandler(OrgNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound(OrgNotFoundException e, HttpServletRequest req) {
    return send(problem(HttpStatus.NOT_FOUND, "not-found", "Không tìm thấy", e.getMessage(), "ORG-404-NOT-FOUND", req));
  }

  @ExceptionHandler(VersionConflictException.class)
  ResponseEntity<ProblemDetail> version(VersionConflictException e, HttpServletRequest req) {
    return send(problem(HttpStatus.CONFLICT, "concurrent-modification", "Dữ liệu đã được người khác cập nhật",
        e.getMessage(), "ORG-409-VERSION-CONFLICT", req));
  }

  @ExceptionHandler(OrgConflictException.class)
  ResponseEntity<ProblemDetail> conflict(OrgConflictException e, HttpServletRequest req) {
    return send(problem(HttpStatus.CONFLICT, "conflict", "Xung đột dữ liệu", e.getMessage(), e.code(), req));
  }

  @ExceptionHandler(ForbiddenOperationException.class)
  ResponseEntity<ProblemDetail> forbidden(ForbiddenOperationException e, HttpServletRequest req) {
    return send(problem(HttpStatus.FORBIDDEN, "forbidden", "Không có quyền thực hiện thao tác này", null,
        "COMMON-403-OUT-OF-SCOPE", req));
  }

  @ExceptionHandler(StepUpRequiredException.class)
  ResponseEntity<ProblemDetail> stepUp(StepUpRequiredException e, HttpServletRequest req) {
    ProblemDetail p = problem(HttpStatus.UNAUTHORIZED, "step-up-required", "Cần xác thực bổ sung",
        "Thao tác này cần xác thực hai lớp gần đây.", "COMMON-401-STEP-UP-REQUIRED", req);
    return ResponseEntity.status(401)
        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_user_authentication\", "
            + "acr_values=\"aal2\", max_age=" + e.maxAgeSeconds())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException e, HttpServletRequest req) {
    if (HttpHeaders.IF_MATCH.equalsIgnoreCase(e.getHeaderName())) {
      return send(problem(HttpStatus.PRECONDITION_REQUIRED, "precondition-required", "Thiếu phiên bản dữ liệu",
          "Yêu cầu cập nhật phải kèm If-Match.", "COMMON-428-PRECONDITION-REQUIRED", req));
    }
    return send(problem(HttpStatus.BAD_REQUEST, "bad-request", "Yêu cầu không hợp lệ",
        "Thiếu tiêu đề " + e.getHeaderName() + ".", "COMMON-400-BAD-REQUEST", req));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException e, HttpServletRequest req) {
    ProblemDetail p = problem(HttpStatus.UNPROCESSABLE_ENTITY, "validation", "Dữ liệu chưa hợp lệ", null,
        "COMMON-422-VALIDATION", req);
    List<Map<String, Object>> errors = new ArrayList<>();
    e.getBindingResult().getFieldErrors().forEach(f -> errors.add(fieldError(f.getField(), "Giá trị không hợp lệ.", null)));
    p.setProperty("errors", errors);
    return send(p);
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
      MissingServletRequestParameterException.class, HandlerMethodValidationException.class,
      IfMatch.MalformedIfMatchException.class, jakarta.validation.ConstraintViolationException.class})
  ResponseEntity<ProblemDetail> badRequest(Exception e, HttpServletRequest req) {
    return send(problem(HttpStatus.BAD_REQUEST, "bad-request", "Yêu cầu không hợp lệ", null,
        "COMMON-400-BAD-REQUEST", req));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ProblemDetail> method(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
    return send(problem(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Phương thức không được hỗ trợ", null,
        "COMMON-405-METHOD-NOT-ALLOWED", req));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ProblemDetail> media(HttpMediaTypeNotSupportedException e, HttpServletRequest req) {
    return send(problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type", "Kiểu nội dung không hỗ trợ",
        null, "COMMON-415-UNSUPPORTED-MEDIA-TYPE", req));
  }

  /** Ràng buộc CSDL là chốt chặn cuối: loại trừ chồng lấn (23P01) là vi phạm nghiệp vụ, trùng khoá là xung đột. */
  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException e, HttpServletRequest req) {
    String state = sqlState(e);
    if ("23P01".equals(state)) {
      ProblemDetail p = problem(HttpStatus.UNPROCESSABLE_ENTITY, "business-rule", "Dữ liệu chưa hợp lệ",
          "Khoảng hiệu lực chồng lấn với dữ liệu đã có.", "ORG-422-OVERLAP", req);
      p.setProperty("errors", List.of(fieldError("validFrom", "Khoảng hiệu lực chồng lấn với dữ liệu đã có.",
          "BR-01-08")));
      return send(p);
    }
    if ("23505".equals(state)) {
      return send(problem(HttpStatus.CONFLICT, "conflict", "Xung đột dữ liệu", "Dữ liệu đã tồn tại.",
          "ORG-409-DUPLICATE", req));
    }
    LOG.warn("Vi phạm toàn vẹn dữ liệu sqlState={}", state);
    return send(problem(HttpStatus.UNPROCESSABLE_ENTITY, "validation", "Dữ liệu chưa hợp lệ", null,
        "COMMON-422-VALIDATION", req));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest req) {
    if (e instanceof org.springframework.web.ErrorResponse known && known.getStatusCode().is4xxClientError()) {
      // Lỗi chuẩn của Spring MVC (không có tuyến, thiếu phần yêu cầu...): giữ mã trạng thái, thêm mã lỗi chung.
      HttpStatus status = HttpStatus.valueOf(known.getStatusCode().value());
      return send(problem(status, "client-error", status.getReasonPhrase(), null,
          "COMMON-" + status.value() + "-" + status.name().replace('_', '-'), req));
    }
    LOG.error("Lỗi không mong đợi khi xử lý {} {}", req.getMethod(), req.getRequestURI(), e);
    return send(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Hệ thống đang bận, vui lòng thử lại sau", null,
        "COMMON-500-INTERNAL", req));
  }

  private static ProblemDetail problem(HttpStatus status, String type, String title, String detail, String code,
      HttpServletRequest req) {
    ProblemDetail p = ProblemDetail.forStatus(status);
    p.setType(URI.create(BASE + type));
    p.setTitle(title);
    if (detail != null) {
      p.setDetail(detail);
    }
    p.setInstance(URI.create(req.getRequestURI()));
    p.setProperty("code", code);
    String trace = traceId(req.getHeader("traceparent"));
    if (trace != null) {
      p.setProperty("traceId", trace);
    }
    return p;
  }

  private static ResponseEntity<ProblemDetail> send(ProblemDetail p) {
    return ResponseEntity.status(p.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
  }

  private static Map<String, Object> fieldError(String field, String message, String rule) {
    return rule == null ? Map.of("field", field, "message", message)
        : Map.of("field", field, "message", message, "rule", rule);
  }

  /** Mã vết từ {@code traceparent} W3C ({@code 00-<trace-id>-<span-id>-<flags>}). */
  static String traceId(String traceparent) {
    if (traceparent == null) {
      return null;
    }
    String[] parts = traceparent.split("-");
    return parts.length == 4 && parts[1].length() == 32 ? parts[1] : null;
  }

  private static String sqlState(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SQLException s) {
        return s.getSQLState();
      }
    }
    return null;
  }
}
