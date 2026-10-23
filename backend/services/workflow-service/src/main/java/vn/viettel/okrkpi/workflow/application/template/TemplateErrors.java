package vn.viettel.okrkpi.workflow.application.template;

import java.util.List;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.workflow.domain.template.TemplateLintFinding;
import vn.viettel.okrkpi.workflow.domain.template.TemplateRuleViolation;
import vn.viettel.okrkpi.workflow.domain.template.TemplateStatus;

/** Ánh xạ vi phạm của mẫu luồng sang mã lỗi của hợp đồng {@code platform.yaml} (SCR-1505). */
public final class TemplateErrors {

  public static final String IMMUTABLE = "WORKFLOW-409-TEMPLATE-IMMUTABLE";
  public static final String NOT_TESTED = "WORKFLOW-409-NOT-TESTED";
  public static final String DRAFT_EXISTS = "WORKFLOW-409-DRAFT-EXISTS";
  public static final String ROLLOUT_WINDOW = "WORKFLOW-409-ROLLOUT-WINDOW";
  public static final String SAME_AUTHOR = "WORKFLOW-403-SAME-AUTHOR";
  public static final String VALIDATION = "COMMON-422-VALIDATION";

  /** Phần MODULE của mã lệch phiên bản ({@code WORKFLOW-409-VERSION-CONFLICT}). */
  public static final String MODULE = "WORKFLOW";

  private TemplateErrors() {}

  /** Vi phạm vòng đời của miền → lỗi của hợp đồng. */
  public static BusinessException of(TemplateRuleViolation v, TemplateStatus current) {
    return switch (v.reason()) {
      case BAT_BIEN -> new BusinessException(IMMUTABLE, v.getMessage());
      case CHUA_KIEM_THU, THIEU_CHU_KY -> new BusinessException(NOT_TESTED, v.getMessage());
      case CUNG_NGUOI_SOAN -> new BusinessException(SAME_AUTHOR, v.getMessage());
      case KHONG_CHO_PHE_DUYET ->
          current.released()
              ? new BusinessException(IMMUTABLE, v.getMessage())
              : new BusinessException(NOT_TESTED, v.getMessage());
    };
  }

  /** Vi phạm linter → 422 với {@code errors[].rule}. */
  public static BusinessException lint(List<TemplateLintFinding> findings) {
    List<ProblemFieldError> errors =
        findings.stream()
            .map(
                f ->
                    ProblemFieldError.of(
                        f.element() == null || f.element().isBlank() ? "bpmnXml" : f.element(),
                        f.message(),
                        f.rule()))
            .toList();
    return new BusinessException(
        VALIDATION, "Mẫu luồng vi phạm quy tắc quản trị của nền tảng.", errors);
  }

  /** Dữ liệu gửi lên chưa hợp lệ ở một trường. */
  public static BusinessException invalid(String field, String message, String rule) {
    return new BusinessException(
        VALIDATION,
        "Dữ liệu gửi lên chưa hợp lệ.",
        List.of(ProblemFieldError.of(field, message, rule)));
  }
}
