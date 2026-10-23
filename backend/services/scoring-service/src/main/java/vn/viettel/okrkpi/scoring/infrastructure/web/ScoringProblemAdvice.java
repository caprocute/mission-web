package vn.viettel.okrkpi.scoring.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.viettel.okrkpi.scoring.application.ScoreSimulationService;
import vn.viettel.okrkpi.scoring.application.ScoreTraceService;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;

/**
 * {@link ScoreTraceService.TraceNotFound} → 404 chuẩn ({@code common.yaml} response NotFound);
 * {@link ScoreSimulationService.Rejected} → 422 kèm lỗi từng trường (UnprocessableEntity).
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class ScoringProblemAdvice {

  private final ProblemFactory problems;

  public ScoringProblemAdvice(ProblemFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(ScoreTraceService.TraceNotFound.class)
  ResponseEntity<ProblemDetail> notFound(
      ScoreTraceService.TraceNotFound e, HttpServletRequest request) {
    ProblemDetail body = problems.standard(404, e.getMessage(), List.of(), request);
    return ResponseEntity.status(404).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  @ExceptionHandler(ScoreSimulationService.Rejected.class)
  ResponseEntity<ProblemDetail> rejected(
      ScoreSimulationService.Rejected e, HttpServletRequest request) {
    List<ProblemFieldError> errors =
        e.issues().stream().map(i -> new ProblemFieldError(i.field(), i.message(), null)).toList();
    ProblemDetail body = problems.standard(422, e.getMessage(), errors, request);
    return ResponseEntity.status(422).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }
}
