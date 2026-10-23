package vn.viettel.okrkpi.workflow.infrastructure.web.approval;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.approval.ApprovalSetupException;
import vn.viettel.okrkpi.workflow.application.approval.StartApprovalCommand;
import vn.viettel.okrkpi.workflow.application.approval.StartApprovalService;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalFlow;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;

/**
 * Cầu nối khởi tạo và kết thúc luồng duyệt cho module lõi (ADR-04 tầng 2; CORE-02-08 dùng cho
 * {@code duyet-ke-hoach-quy}, cũng dùng được cho {@code duyet-okr}, {@code duyet-de-nghi}). Chỉ
 * client dịch vụ vai trò {@code service_client}; tenant đích đọc từ {@code X-Tenant-Id}. Khởi tạo
 * idempotent theo (tenant, mẫu, khoá nghiệp vụ, phiên bản đối tượng) nên lõi gọi lại an toàn; hợp
 * đồng {@code platform.yaml} chưa có thao tác này nên không xuất ra OpenAPI (wiki trang 14).
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/workflow")
public class InternalApprovalController {

  static final String VALIDATION = "COMMON-422-VALIDATION";

  private final StartApprovalService approvals;

  public InternalApprovalController(StartApprovalService approvals) {
    this.approvals = approvals;
  }

  /** Khởi tạo luồng duyệt; trả mã tiến trình và cờ vừa tạo. */
  @PostMapping("/approvals")
  public Map<String, Object> start(@RequestBody Map<String, Object> body) {
    List<ProblemFieldError> errors = new ArrayList<>();
    ApprovalTemplate template = template(body, errors);
    UUID businessId = uuid(body, "businessId", true, errors);
    UUID unit = uuid(body, "subjectUnitId", true, errors);
    ApprovalFlow flow = ApprovalFlow.parse(String.valueOf(body.get("flow"))).orElse(null);
    if (flow == null) {
      errors.add(ProblemFieldError.of("flow", "Luồng đánh giá không thuộc danh mục"));
    }
    long version = number(body.get("objectVersion"), "objectVersion", errors);
    Object title = body.get("title");
    if (!(title instanceof String)) {
      errors.add(ProblemFieldError.of("title", "Thiếu tiêu đề việc"));
    }
    UUID initiator = uuid(body, "initiatorId", false, errors);
    UUID period = uuid(body, "periodId", false, errors);
    UUID task = uuid(body, "taskId", false, errors);
    Integer planVersionNo =
        body.get("planVersionNo") == null
            ? null
            : (int) number(body.get("planVersionNo"), "planVersionNo", errors);
    if (!errors.isEmpty()) {
      throw new BusinessException(VALIDATION, "Yêu cầu khởi tạo luồng duyệt chưa hợp lệ.", errors);
    }
    try {
      StartApprovalService.Result r =
          approvals.start(
              new StartApprovalCommand(
                  TenantContext.require().value(),
                  template,
                  businessId,
                  version,
                  flow,
                  unit,
                  Boolean.TRUE.equals(body.get("headOfUnit")),
                  initiator,
                  period,
                  (String) title,
                  string(body.get("policyPackVersion")),
                  planVersionNo,
                  task,
                  string(body.get("causeClaimed"))));
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("processInstanceId", r.processInstanceId());
      out.put("created", r.created());
      return out;
    } catch (ApprovalSetupException e) {
      throw new BusinessException(
          VALIDATION, e.getMessage(), List.of(ProblemFieldError.of("body", e.getMessage())));
    }
  }

  /** Kết thúc tiến trình còn chờ người duyệt của đối tượng; trả số tiến trình đã kết thúc. */
  @PostMapping("/approvals/cancel")
  public Map<String, Object> cancel(@RequestBody Map<String, Object> body) {
    List<ProblemFieldError> errors = new ArrayList<>();
    ApprovalTemplate template = template(body, errors);
    UUID businessId = uuid(body, "businessId", true, errors);
    if (!errors.isEmpty()) {
      throw new BusinessException(VALIDATION, "Yêu cầu kết thúc luồng duyệt chưa hợp lệ.", errors);
    }
    int n =
        approvals.cancel(
            TenantContext.require().value(), template, businessId, string(body.get("reason")));
    return Map.of("cancelled", n);
  }

  private static ApprovalTemplate template(
      Map<String, Object> body, List<ProblemFieldError> errors) {
    Object code = body.get("template");
    Optional<ApprovalTemplate> t =
        java.util.Arrays.stream(ApprovalTemplate.values())
            .filter(v -> v.code().equals(code))
            .findFirst();
    if (t.isEmpty()) {
      errors.add(ProblemFieldError.of("template", "Mẫu luồng duyệt không thuộc danh mục"));
      return null;
    }
    return t.get();
  }

  private static UUID uuid(
      Map<String, Object> body, String field, boolean required, List<ProblemFieldError> errors) {
    Object v = body.get(field);
    if (v == null) {
      if (required) {
        errors.add(ProblemFieldError.of(field, "Bắt buộc"));
      }
      return null;
    }
    try {
      return UUID.fromString(String.valueOf(v));
    } catch (IllegalArgumentException e) {
      errors.add(ProblemFieldError.of(field, "Mã không hợp lệ"));
      return null;
    }
  }

  private static long number(Object v, String field, List<ProblemFieldError> errors) {
    if (v instanceof Number n) {
      return n.longValue();
    }
    errors.add(ProblemFieldError.of(field, "Phải là số"));
    return 0;
  }

  private static String string(Object v) {
    return v instanceof String s ? s : null;
  }
}
