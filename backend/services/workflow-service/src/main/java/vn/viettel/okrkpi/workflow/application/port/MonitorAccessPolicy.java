package vn.viettel.okrkpi.workflow.application.port;

import vn.viettel.okrkpi.shared.authz.AuthzResource;

/**
 * Kiểm quyền SCR-1506 qua OPA theo khoá chức năng của ma trận ({@code xem}, {@code so-do}, {@code
 * chuyen-nguoi-xu-ly}, {@code gia-han-buoc}); phạm vi Tn hay N do OPA quyết định theo {@code
 * tenant_id} của tài nguyên, mã nguồn không đặt danh sách vai trò.
 */
public interface MonitorAccessPolicy {

  void checkView(AuthzResource resource);

  void checkDiagram(AuthzResource resource);

  void checkReassign(AuthzResource resource);

  void checkExtend(AuthzResource resource);
}
