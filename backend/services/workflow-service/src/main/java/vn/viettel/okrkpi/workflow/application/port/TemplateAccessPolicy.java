package vn.viettel.okrkpi.workflow.application.port;

import vn.viettel.okrkpi.shared.authz.AuthzResource;

/**
 * Kiểm quyền SCR-1505 qua OPA theo khoá chức năng của ma trận ({@code thu-vien}, {@code soan},
 * {@code chay-test}, {@code de-nghi-phat-hanh}, {@code phe-duyet}); không đặt danh sách vai trò
 * trong mã.
 */
public interface TemplateAccessPolicy {

  void checkLibrary(AuthzResource resource);

  void checkAuthor(AuthzResource resource);

  void checkTest(AuthzResource resource);

  void checkSubmit(AuthzResource resource);

  /** Bốn mắt và bước lên AAL2 ({@code SCR-1505.phe-duyet}). */
  void checkApprove(AuthzResource resource);
}
