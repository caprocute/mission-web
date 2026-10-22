package vn.viettel.okrkpi.evidence.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.viettel.okrkpi.evidence.application.UploadService;
import vn.viettel.okrkpi.shared.error.ProblemFactory;

/** {@link UploadService.FileNotFound} → 404 chuẩn ({@code common.yaml} response NotFound). */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class FilesProblemAdvice {

  private final ProblemFactory problems;

  public FilesProblemAdvice(ProblemFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(UploadService.FileNotFound.class)
  ResponseEntity<ProblemDetail> notFound(UploadService.FileNotFound e, HttpServletRequest request) {
    ProblemDetail body = problems.standard(404, e.getMessage(), List.of(), request);
    return ResponseEntity.status(404).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }
}
