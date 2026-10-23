package vn.viettel.okrkpi.shared.tenant;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.viettel.okrkpi.shared.error.ProblemFactory;

/**
 * Yêu cầu tới thao tác cần tenant mà ngữ cảnh không có tenant (không có JWT, hoặc token không mang
 * claim tenant) nhận 403 Problem Details như {@link TenantContextFilter}, thay cho 500 của bộ xử lý
 * lỗi chung.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class TenantProblemHandler {

  static final String DETAIL = "Phiên đăng nhập không gắn với đơn vị triển khai nào";

  private final ProblemFactory problems;

  public TenantProblemHandler(ProblemFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(MissingTenantException.class)
  public ResponseEntity<ProblemDetail> handleMissingTenant(
      MissingTenantException ex, HttpServletRequest request) {
    return ProblemFactory.toResponse(problems.standard(403, DETAIL, List.of(), request), null);
  }
}
