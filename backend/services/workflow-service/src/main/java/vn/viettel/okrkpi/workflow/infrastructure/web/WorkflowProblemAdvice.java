package vn.viettel.okrkpi.workflow.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.workflow.application.WorkflowNotFoundException;
import vn.viettel.okrkpi.workflow.infrastructure.web.binding.InternalBindingController;
import vn.viettel.okrkpi.workflow.infrastructure.web.model.WorkflowBatchCompletion;
import vn.viettel.okrkpi.workflow.infrastructure.web.monitor.WorkflowMonitorController;
import vn.viettel.okrkpi.workflow.infrastructure.web.template.InternalTemplateReleaseController;
import vn.viettel.okrkpi.workflow.infrastructure.web.template.WorkflowTemplateController;

/**
 * Lỗi riêng của API Workflow, chạy trước bộ xử lý chung của thư viện nền:
 *
 * <ul>
 *   <li>không tìm thấy (kể cả khác tenant) → 404 chuẩn;
 *   <li>lô quá 200 mục → 422 {@code WORKFLOW-422-BATCH-TOO-LARGE} (hợp đồng {@code
 *       batchCompleteWorkflowTasks}) thay cho lỗi kiểm dữ liệu chung;
 *   <li>hàng đợi lô đầy → 503 kèm {@code Retry-After}.
 * </ul>
 */
@RestControllerAdvice(
    assignableTypes = {
      WorkflowController.class,
      InternalBindingController.class,
      WorkflowTemplateController.class,
      InternalTemplateReleaseController.class,
      WorkflowMonitorController.class
    })
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkflowProblemAdvice {

  static final String BATCH_TOO_LARGE = "WORKFLOW-422-BATCH-TOO-LARGE";
  static final String VALIDATION = "COMMON-422-VALIDATION";

  private final ProblemFactory problems;

  public WorkflowProblemAdvice(ProblemFactory problems) {
    this.problems = problems;
  }

  @ExceptionHandler(WorkflowNotFoundException.class)
  public ResponseEntity<ProblemDetail> notFound(
      WorkflowNotFoundException ex, HttpServletRequest request) {
    return ProblemFactory.toResponse(problems.standard(404, null, List.of(), request), null);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> invalidBody(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    boolean tooLarge =
        ex.getTarget() instanceof WorkflowBatchCompletion body
            && body.getItems() != null
            && body.getItems().size() > 200;
    if (tooLarge) {
      return ProblemFactory.toResponse(
          problems.fromCode(
              problems.catalog().require(BATCH_TOO_LARGE),
              "Mỗi lần duyệt hàng loạt tối đa 200 việc.",
              List.of(ProblemFieldError.of("items", "Tối đa 200 việc.", "maxItems")),
              null,
              request),
          null);
    }
    List<ProblemFieldError> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(WorkflowProblemAdvice::fieldError)
            .toList();
    return ProblemFactory.toResponse(
        problems.fromCode(
            problems.catalog().require(VALIDATION),
            "Dữ liệu gửi lên chưa hợp lệ.",
            errors,
            null,
            request),
        null);
  }

  @ExceptionHandler(RejectedExecutionException.class)
  public ResponseEntity<ProblemDetail> busy(
      RejectedExecutionException ex, HttpServletRequest request) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "30");
    return ProblemFactory.toResponse(
        problems.standard(
            503, "Hệ thống đang xử lý nhiều lô duyệt, vui lòng thử lại sau.", List.of(), request),
        headers);
  }

  private static ProblemFieldError fieldError(FieldError error) {
    String rule = error.getCode() == null ? null : error.getCode();
    return ProblemFieldError.of(error.getField(), "Giá trị không hợp lệ.", rule);
  }
}
