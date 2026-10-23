package vn.viettel.okrkpi.docgen.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.viettel.okrkpi.docgen.application.render.DocgenNotFoundException;
import vn.viettel.okrkpi.docgen.application.render.PreviewRateLimiter;
import vn.viettel.okrkpi.shared.error.ProblemFactory;

/**
 * {@link DocgenNotFoundException} → 404 chuẩn ({@code common.yaml} response NotFound); vượt giới
 * hạn xem trước → 429.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class DocgenProblemAdvice {

  private final ProblemFactory problems;

  public DocgenProblemAdvice(ProblemFactory problems) {
    this.problems = problems;
  }

  /** Vượt giới hạn xem trước (DV-11-02): 429 kèm {@code Retry-After}. */
  @ExceptionHandler(PreviewRateLimiter.Exceeded.class)
  ResponseEntity<ProblemDetail> tooMany(PreviewRateLimiter.Exceeded e, HttpServletRequest request) {
    ProblemDetail body = problems.standard(429, null, List.of(), request);
    return ResponseEntity.status(429)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(DocgenNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound(DocgenNotFoundException e, HttpServletRequest request) {
    ProblemDetail body = problems.standard(404, e.getMessage(), List.of(), request);
    return ResponseEntity.status(404).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }
}
