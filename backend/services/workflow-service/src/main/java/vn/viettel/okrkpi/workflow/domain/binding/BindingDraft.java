package vn.viettel.okrkpi.workflow.domain.binding;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tham số luồng duyệt của một loại quy trình trong một phiên bản Policy Pack (SCR-1504): mẫu đã
 * chọn, phạm vi, tham số và kết quả linter, bộ hồ sơ mẫu lúc lưu.
 *
 * @param id mã bản ghi
 * @param tenantId tenant
 * @param packVersionId phiên bản Policy Pack sở hữu
 * @param processKind loại quy trình ({@code wf_binding.process_kind})
 * @param templateKey mã mẫu
 * @param templateVersionNo phiên bản mẫu
 * @param scope phạm vi áp dụng
 * @param params tham số truyền vào tiến trình
 * @param lint kết quả linter lúc lưu
 * @param tests kết quả bộ hồ sơ mẫu lúc lưu
 * @param status trạng thái
 * @param rowVersion khoá lạc quan
 */
public record BindingDraft(
    UUID id,
    UUID tenantId,
    UUID packVersionId,
    String processKind,
    String templateKey,
    int templateVersionNo,
    BindingScope scope,
    Map<String, Object> params,
    LintResult lint,
    TestSummary tests,
    BindingStatus status,
    long rowVersion) {

  public BindingDraft {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(packVersionId, "packVersionId");
    Objects.requireNonNull(processKind, "processKind");
    Objects.requireNonNull(templateKey, "templateKey");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(status, "status");
    params = params == null ? Map.of() : params;
  }
}
