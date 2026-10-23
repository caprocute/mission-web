package vn.viettel.okrkpi.workflow.domain.binding;

import java.util.Objects;

/**
 * Một phiên bản mẫu luồng đã phát hành trong danh mục chọn mẫu (SCR-1504).
 *
 * @param key mã mẫu ({@code wf_template.template_code})
 * @param name tên hiển thị
 * @param versionNo phiên bản mẫu
 * @param processKind loại quy trình mẫu phục vụ
 * @param rules khai báo tham số
 */
public record TemplateInfo(
    String key, String name, int versionNo, String processKind, TemplateParamRules rules) {

  public TemplateInfo {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(processKind, "processKind");
    Objects.requireNonNull(rules, "rules");
    if (versionNo < 1) {
      throw new IllegalArgumentException("Phiên bản mẫu phải từ 1");
    }
  }
}
