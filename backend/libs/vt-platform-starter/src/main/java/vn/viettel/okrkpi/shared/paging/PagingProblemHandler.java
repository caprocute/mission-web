package vn.viettel.okrkpi.shared.paging;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;

/** Tham số phân trang sai → 400 {@code application/problem+json} kèm {@code errors[]}. */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class PagingProblemHandler {

  private final ProblemFactory problems;

  public PagingProblemHandler(ProblemFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(InvalidPageRequestException.class)
  public ResponseEntity<ProblemDetail> handleInvalidPage(
      InvalidPageRequestException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problems.standard(
            400,
            ex.userMessage(),
            List.of(ProblemFieldError.of(ex.parameter(), ex.userMessage())),
            request);
    return ProblemFactory.toResponse(pd, null);
  }
}
