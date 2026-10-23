package vn.viettel.okrkpi.workflow.application.monitor;

import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.workflow.application.port.MonitorAccessPolicy;

/**
 * {@link MonitorAccessPolicy} bằng {@link RequiresPermission}: khoá chức năng {@code SCR-1506.*}
 * của ma trận phân quyền; OPA quyết định vai trò, phạm vi (AC-02 trong tenant, AC-01 cả cell) và
 * phiên AAL2 của vai trò quản trị. Phần thân trống là chủ ý.
 *
 * <p>Không khai {@code module}, {@code method}: dòng API {@code workflow} của ma trận không có
 * {@code platform_admin}, nên kèm yêu cầu API thì OPA từ chối AC-01 dù ma trận chức năng cho phép
 * (wiki trang 14 mục DV-06-10).
 */
@Component
public class OpaMonitorAccessPolicy implements MonitorAccessPolicy {

  @Override
  @RequiresPermission(action = "SCR-1506.xem")
  public void checkView(AuthzResource resource) {
    // Quyết định do PermissionInterceptor thực hiện trước khi vào thân.
  }

  @Override
  @RequiresPermission(action = "SCR-1506.so-do")
  public void checkDiagram(AuthzResource resource) {
    // Quyết định do PermissionInterceptor thực hiện trước khi vào thân.
  }

  @Override
  @RequiresPermission(action = "SCR-1506.chuyen-nguoi-xu-ly")
  public void checkReassign(AuthzResource resource) {
    // Quyết định do PermissionInterceptor thực hiện trước khi vào thân.
  }

  @Override
  @RequiresPermission(action = "SCR-1506.gia-han-buoc")
  public void checkExtend(AuthzResource resource) {
    // Quyết định do PermissionInterceptor thực hiện trước khi vào thân.
  }
}
