package vn.viettel.okrkpi.shared.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;

/**
 * Problem Details cho lỗi quy ước {@code Idempotency-Key} không có mã riêng trong hợp đồng: thiếu
 * hoặc sai header → 400 kèm {@code errors[]} trỏ vào header; cùng khoá đang xử lý → 409 {@code type
 * .../conflict} kèm {@code Retry-After}. Lệch nội dung là {@link
 * vn.viettel.okrkpi.shared.error.BusinessException} nên đi qua bộ xử lý chung (422 {@code
 * COMMON-422-IDEMPOTENCY-MISMATCH}).
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class IdempotencyProblemHandler {

  static final String MISSING = "Thao tác này cần mã chống gửi trùng.";
  static final String INVALID = "Mã chống gửi trùng không hợp lệ (tối đa 64 ký tự).";
  static final String IN_PROGRESS =
      "Yêu cầu trước đó với cùng mã chống gửi trùng đang được xử lý. Vui lòng thử lại sau ít giây.";

  private final ProblemFactory problems;

  public IdempotencyProblemHandler(ProblemFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(IdempotencyExceptions.KeyRequired.class)
  public ResponseEntity<ProblemDetail> handleKeyRequired(
      IdempotencyExceptions.KeyRequired ex, HttpServletRequest request) {
    String message = ex.present() ? INVALID : MISSING;
    ProblemDetail pd =
        problems.standard(
            400,
            message,
            List.of(ProblemFieldError.of(IdempotencyInterceptor.HEADER, message)),
            request);
    return ProblemFactory.toResponse(pd, null);
  }

  @ExceptionHandler(IdempotencyExceptions.InProgress.class)
  public ResponseEntity<ProblemDetail> handleInProgress(
      IdempotencyExceptions.InProgress ex, HttpServletRequest request) {
    ProblemDetail pd = problems.standard(409, IN_PROGRESS, List.of(), request);
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, ex.retryAfterSeconds())));
    return ProblemFactory.toResponse(pd, headers);
  }
}
