package vn.viettel.okrkpi.workflow.application.port;

import vn.viettel.okrkpi.shared.authz.AuthzResource;

/**
 * Kiểm quyền chức năng và tách vai trò qua OPA (ADR-21, ma trận {@code SCR-0203}); từ chối thì ném
 * {@link vn.viettel.okrkpi.shared.error.BusinessException} với mã của danh mục.
 */
public interface ApprovalAccessPolicy {

  /** Xem hộp việc và lịch sử ({@code SCR-0203.xem}). */
  void checkView(AuthzResource resource);

  /** Duyệt một việc ({@code SCR-0203.duyet}); người khởi tạo không được tự duyệt (BR-02-08). */
  void checkApprove(AuthzResource resource);

  /** Duyệt một mục trong lô ({@code SCR-0203.duyet-lo}). */
  void checkBatchApprove(AuthzResource resource);

  /** Trả lại ({@code SCR-0203.tra-lai}). */
  void checkReturn(AuthzResource resource);
}
