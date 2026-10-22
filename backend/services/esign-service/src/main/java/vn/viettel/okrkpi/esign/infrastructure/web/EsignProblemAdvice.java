package vn.viettel.okrkpi.esign.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.viettel.okrkpi.esign.application.sign.EsignNotFoundException;
import vn.viettel.okrkpi.shared.error.ProblemFactory;

/** Không tìm thấy yêu cầu ký hoặc hồ sơ nguồn → 404 chuẩn ({@code common.yaml} NotFound). */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class EsignProblemAdvice {

  private final ProblemFactory problems;

  public EsignProblemAdvice(ProblemFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(EsignNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound(EsignNotFoundException e, HttpServletRequest request) {
    ProblemDetail body = problems.standard(404, e.getMessage(), List.of(), request);
    return ResponseEntity.status(404).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }
}
