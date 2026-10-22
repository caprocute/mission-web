package vn.viettel.okrkpi.policy.application.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng ra tới API nội bộ {@code wf_binding} và danh mục mẫu luồng của workflow-service (SVC-06,
 * DV-06-08) cho lớp công bố của MOD-05 (CORE-05-11, QT-15, SCR-1504). Tài liệu trao đổi giữ dạng
 * lược đồ của hợp đồng ({@code WorkflowBinding}, {@code WorkflowSimulateResult}, {@code
 * PolicyWorkflowTemplateList}, {@code PolicyWorkflowTemplateVersion}).
 *
 * <p>Lỗi: SVC-06 không gọi được, quá thời gian, mạch mở hoặc 5xx thành {@code
 * DependencyUnavailableException} (503 kèm {@code Retry-After}); 409 thành {@code
 * VersionConflictException}; 422 thành {@code POLICY-422-WF-PARAMS} giữ danh sách quy tắc vi phạm
 * (MSG-15-09).
 */
public interface WorkflowBindings {

  /** Tài liệu kèm phiên bản dòng ở SVC-06 (ETag của hợp đồng). */
  record Versioned(Map<String, Object> body, long rowVersion) {}

  /** Các bản ghi {@code wf_binding} gắn với phiên bản Policy Pack. */
  List<Map<String, Object>> list(TenantId tenant, UUID packVersionId);

  /** Bản ghi theo loại quy trình; không có thì rỗng. */
  Optional<Versioned> find(TenantId tenant, UUID packVersionId, String processKind);

  /**
   * Lưu bản ghi nháp với {@code If-Match} là {@code expectedRowVersion} ({@code 0} cho lần đầu);
   * SVC-06 kiểm tham số theo {@code paramSchema}, chạy linter và test của mẫu.
   */
  Versioned save(
      TenantId tenant,
      UUID packVersionId,
      String processKind,
      long expectedRowVersion,
      String idempotencyKey,
      Map<String, Object> binding);

  /** Mô phỏng trên engine thử của SVC-06; không ghi gì. */
  Map<String, Object> simulate(TenantId tenant, Map<String, Object> request, String idempotencyKey);

  /** Danh mục mẫu luồng {@code HIEU_LUC}. */
  Map<String, Object> templates(
      TenantId tenant, String processKind, String q, int limit, String cursor);

  /** Khai báo tham số của một phiên bản mẫu; không có thì rỗng. */
  Optional<Versioned> template(TenantId tenant, String templateKey, int versionNo);
}
