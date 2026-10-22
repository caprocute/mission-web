package vn.viettel.okrkpi.workflow.application;

import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.workflow.application.port.ApprovalAccessPolicy;

/**
 * Hiện thực {@link ApprovalAccessPolicy} bằng {@link RequiresPermission} của thư viện nền: bộ chặn
 * dựng {@code OpaInput} (khoá chức năng ma trận, {@code module=workflow}, tài nguyên truyền vào),
 * hỏi OPA và ném {@code BusinessException} khi từ chối. Phần thân trống là chủ ý.
 *
 * <p>Không khai {@code operationId}: danh mục thao tác gán {@code errorModule=PLATFORM} cho thao
 * tác Workflow nên từ chối tách vai trò sẽ ra mã của module khác; khai {@code module=workflow} để
 * từ chối ra đúng {@code WORKFLOW-403-SELF-APPROVAL} (MSG-02-11). Bốn thao tác đều AAL1.
 */
@Component
public class OpaApprovalAccessPolicy implements ApprovalAccessPolicy {

  @Override
  @RequiresPermission(action = "SCR-0203.xem", module = "workflow", method = "GET")
  public void checkView(AuthzResource resource) {
    // Quyết định do PermissionInterceptor thực hiện trước khi vào thân.
  }

  @Override
  @RequiresPermission(action = "SCR-0203.duyet", module = "workflow", method = "POST")
  public void checkApprove(AuthzResource resource) {
    // Quyết định do PermissionInterceptor thực hiện trước khi vào thân.
  }

  @Override
  @RequiresPermission(action = "SCR-0203.duyet-lo", module = "workflow", method = "POST")
  public void checkBatchApprove(AuthzResource resource) {
    // Quyết định do PermissionInterceptor thực hiện trước khi vào thân.
  }

  @Override
  @RequiresPermission(action = "SCR-0203.tra-lai", module = "workflow", method = "POST")
  public void checkReturn(AuthzResource resource) {
    // Quyết định do PermissionInterceptor thực hiện trước khi vào thân.
  }
}
