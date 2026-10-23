package vn.viettel.okrkpi.oversight.infrastructure.web;

import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintHandlingService.ComplaintAuthz;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;

/**
 * Hỏi OPA chức năng SCR-1202 với người đã tham gia hồ sơ (ràng buộc 21.1, BR-12-04): từ chối vì
 * tách vai trò được {@code PermissionInterceptor} đổi sang {@code
 * OVERSIGHT-403-SEPARATION-OF-DUTIES} (MSG-12-05). Dịch vụ kiểm lại cùng quy tắc ở máy chủ.
 */
@Component
public class ComplaintGuard {

  @RequiresPermission(
      action = "SCR-1202.tiep-nhan",
      module = "oversight",
      method = "POST",
      resourceType = ComplaintAuthz.TYPE)
  public void handle(ComplaintAuthz resource) {
    // Kiểm ở PermissionInterceptor.
  }
}
