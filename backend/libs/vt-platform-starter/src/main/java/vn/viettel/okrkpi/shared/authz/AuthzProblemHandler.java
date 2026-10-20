package vn.viettel.okrkpi.shared.authz;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.viettel.okrkpi.shared.error.ProblemTypes;

/**
 * Chuyển {@link StepUpRequiredException} thành phản hồi HTTP 401 kèm header {@code
 * WWW-Authenticate: Bearer error="insufficient_user_authentication", acr_values="aal2",
 * max_age=<giây>} (RFC 9470) và thân lỗi RFC 9457 (NEN-014, {@code common.yaml}).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthzProblemHandler {

  public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;
  public static final String STEP_UP_CODE = "COMMON-401-STEP-UP-REQUIRED";
  public static final String STEP_UP_MSG = "MSG-00-02";

  @ExceptionHandler(StepUpRequiredException.class)
  public ResponseEntity<ProblemDetail> handleStepUpRequired(
      StepUpRequiredException ex, HttpServletRequest request) {
    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
    pd.setType(URI.create(ProblemTypes.BASE_URI + "unauthorized"));
    pd.setTitle("Chưa xác thực hoặc cần xác thực bước lên (AAL2)");
    pd.setDetail(ex.getMessage());
    if (request != null && request.getRequestURI() != null) {
      pd.setInstance(URI.create(request.getRequestURI()));
    }
    pd.setProperty("code", STEP_UP_CODE);
    pd.setProperty("msg", STEP_UP_MSG);
    pd.setProperty("acr_values", StepUpRequiredException.REQUIRED_ACR);
    pd.setProperty("max_age", ex.getMaxAgeSeconds());
    if (ex.getOperationId() != null && !ex.getOperationId().isBlank()) {
      pd.setProperty("params", Map.of("operationId", ex.getOperationId()));
    }
    currentTraceId().ifPresent(t -> pd.setProperty("traceId", t));

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(PROBLEM_JSON);
    headers.set(HttpHeaders.WWW_AUTHENTICATE, ex.wwwAuthenticateHeader());
    return new ResponseEntity<>(pd, headers, HttpStatus.UNAUTHORIZED);
  }

  private static Optional<String> currentTraceId() {
    String mdc = MDC.get("trace.id");
    if (mdc == null || mdc.isBlank()) {
      mdc = MDC.get("traceId");
    }
    if (mdc != null && !mdc.isBlank()) {
      return Optional.of(mdc);
    }
    return Optional.of(UUID.randomUUID().toString().replace("-", ""));
  }
}
