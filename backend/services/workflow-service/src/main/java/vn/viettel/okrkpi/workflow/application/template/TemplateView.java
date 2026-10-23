package vn.viettel.okrkpi.workflow.application.template;

import java.util.Objects;
import vn.viettel.okrkpi.workflow.domain.template.TemplateVersion;

/**
 * Phiên bản mẫu kèm số liệu của thư viện ({@code WorkflowTemplate} của hợp đồng).
 *
 * @param template phiên bản mẫu
 * @param tenantBindingCount số tenant đang dùng (đếm từ {@code wf_binding})
 * @param testCount số ca của lần chạy test gần nhất, hoặc số kịch bản khi chưa chạy
 * @param mergeRequestUrl merge request đã mở, có thể rỗng
 */
public record TemplateView(
    TemplateVersion template, int tenantBindingCount, int testCount, String mergeRequestUrl) {

  public TemplateView {
    Objects.requireNonNull(template, "template");
  }
}
