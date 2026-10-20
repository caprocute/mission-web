package vn.viettel.okrkpi.shared.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thay {@code BasicErrorController} của Spring Boot: lỗi xảy ra ngoài controller (bộ lọc servlet,
 * {@code sendError}, lỗi container) cũng trả {@code application/problem+json} thay cho JSON lỗi mặc
 * định của Spring Boot, không bao giờ kèm thông điệp ngoại lệ hay dấu vết ngăn xếp.
 */
@RestController
@RequestMapping("${server.error.path:${error.path:/error}}")
public class ProblemErrorController implements ErrorController {

  private static final Logger log = LoggerFactory.getLogger(ProblemErrorController.class);

  private final ProblemFactory problems;

  public ProblemErrorController(ProblemFactory problems) {
    this.problems = problems;
  }

  /**
   * Trang lỗi nhận mọi phương thức (như {@code BasicErrorController}) vì container chuyển tiếp
   * nguyên phương thức của yêu cầu gốc. Chỉ dựng phản hồi lỗi, không đổi trạng thái, nên cảnh báo
   * CSRF của Find Security Bugs được loại trừ có lý do trong {@code spotbugs-exclude.xml}.
   */
  @RequestMapping
  public ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
    int status = status(request);
    Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    String instance =
        uri instanceof String s ? ProblemFactory.safePath(s) : ProblemFactory.safePath("/");
    ProblemDetail pd = problems.standard(status, null, List.of(), request, instance);
    if (status >= 500) {
      Object ex = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
      log.error(
          "Lỗi máy chủ ngoài controller status={} traceId={} instance={}",
          status,
          pd.getProperties().get(ProblemFactory.TRACE_ID),
          instance,
          ex instanceof Throwable t ? t : null);
    }
    return ProblemFactory.toResponse(pd, null);
  }

  private static int status(HttpServletRequest request) {
    Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    if (code instanceof Integer i && i >= 400 && i <= 599) {
      return i;
    }
    return 500;
  }
}
