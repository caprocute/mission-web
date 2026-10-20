package vn.viettel.okrkpi.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Ánh xạ lỗi quyền của Spring Security ném ra trong controller hoặc use case (chỉ nạp khi có Spring
 * Security): từ chối quyền → 403 {@code COMMON-403-OUT-OF-SCOPE} ({@code common.yaml} response
 * Forbidden); chưa xác thực → 401. Lỗi ở tầng bộ lọc bảo mật đi qua {@link ProblemErrorController}.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class SecurityProblemHandler {

  private final ProblemFactory problems;

  public SecurityProblemHandler(ProblemFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    return ProblemFactory.toResponse(problems.standard(403, null, List.of(), request), null);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ProblemDetail> handleAuthentication(
      AuthenticationException ex, HttpServletRequest request) {
    return ProblemFactory.toResponse(problems.standard(401, null, List.of(), request), null);
  }
}
