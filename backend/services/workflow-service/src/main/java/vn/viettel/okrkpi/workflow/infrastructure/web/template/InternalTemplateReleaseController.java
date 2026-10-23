package vn.viettel.okrkpi.workflow.infrastructure.web.template;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.workflow.application.template.TemplateErrors;
import vn.viettel.okrkpi.workflow.application.template.WorkflowTemplateService;

/**
 * Lời gọi lại nội bộ của pipeline repo mẫu luồng sau khi ký gói mẫu bằng cosign (BS-01 "Đồng bộ với
 * kho mã"): ghi chữ ký và trả lời bước chờ ký của saga phát hành. Chỉ client dịch vụ ({@code
 * service_client}, như mọi {@code /internal/**}); hợp đồng chưa có thao tác này nên không xuất ra
 * tài liệu OpenAPI (wiki trang 14).
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/workflow")
public class InternalTemplateReleaseController {

  private final WorkflowTemplateService templates;

  public InternalTemplateReleaseController(WorkflowTemplateService templates) {
    this.templates = templates;
  }

  /**
   * Thân: {@code signature} (base64 của chữ ký cosign), {@code pipelineRef} (lần chạy pipeline).
   */
  @PostMapping("/templates/{id}/signature")
  public ResponseEntity<Map<String, Object>> signed(
      @PathVariable UUID id, @RequestBody Map<String, Object> body) {
    Object raw = body == null ? null : body.get("signature");
    byte[] signature;
    try {
      signature = raw instanceof String s ? Base64.getDecoder().decode(s) : null;
    } catch (IllegalArgumentException e) {
      signature = null;
    }
    if (signature == null || signature.length == 0) {
      throw new BusinessException(
          TemplateErrors.VALIDATION,
          "Dữ liệu gửi lên chưa hợp lệ.",
          List.of(ProblemFieldError.of("signature", "Chữ ký phải là chuỗi base64.", "base64")));
    }
    Object ref = body.get("pipelineRef");
    String pipelineRef = ref == null ? null : String.valueOf(ref);
    if (pipelineRef != null && pipelineRef.length() > 128) {
      pipelineRef = pipelineRef.substring(0, 128);
    }
    boolean replied = templates.recordSignature(id, signature, pipelineRef);
    return ResponseEntity.ok(Map.of("templateId", id.toString(), "sagaNotified", replied));
  }
}
