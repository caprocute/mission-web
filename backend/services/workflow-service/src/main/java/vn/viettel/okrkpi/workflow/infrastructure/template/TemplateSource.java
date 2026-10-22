package vn.viettel.okrkpi.workflow.infrastructure.template;

import java.util.Objects;

/**
 * Một mẫu luồng cần kiểm: nội dung BPMN, khai báo đi kèm và tenant của lần triển khai.
 *
 * @param resourceName tên tài nguyên BPMN
 * @param bpmnXml nội dung BPMN 2.0
 * @param descriptor khai báo, {@code null} khi thiếu tệp khai báo
 * @param deploymentTenantId tenant của lần triển khai; mẫu hợp lệ luôn rỗng
 */
public record TemplateSource(
    String resourceName, String bpmnXml, TemplateDescriptor descriptor, String deploymentTenantId) {

  public TemplateSource {
    Objects.requireNonNull(resourceName, "resourceName");
    Objects.requireNonNull(bpmnXml, "bpmnXml");
  }
}
