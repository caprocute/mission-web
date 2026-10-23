package vn.viettel.okrkpi.workflow.application.template;

import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.workflow.application.port.TemplateAccessPolicy;

/**
 * {@link TemplateAccessPolicy} bằng {@link RequiresPermission}: khoá chức năng {@code SCR-1505.*}
 * của ma trận phân quyền, OPA quyết định vai trò (vai trò con {@code wf_template_author} của AC-01,
 * AC-09 phê duyệt) và bốn mắt; mã nguồn không đặt danh sách vai trò. Phần thân trống là chủ ý.
 *
 * <p>Xem tiến độ triển khai dùng {@code thu-vien} vì hợp đồng {@code listWorkflowTemplateRollouts}
 * cho cả người phê duyệt xem, trong khi khoá {@code trien-khai-cell} của ma trận chỉ cấp cho người
 * soạn (wiki trang 14).
 */
@Component
public class OpaTemplateAccessPolicy implements TemplateAccessPolicy {

  @Override
  @RequiresPermission(action = "SCR-1505.thu-vien", module = "workflow", method = "GET")
  public void checkLibrary(AuthzResource resource) {
    // Quyết định do PermissionInterceptor thực hiện trước khi vào thân.
  }

  @Override
  @RequiresPermission(action = "SCR-1505.soan", module = "workflow", method = "POST")
  public void checkAuthor(AuthzResource resource) {
    // Quyết định do PermissionInterceptor thực hiện trước khi vào thân.
  }

  @Override
  @RequiresPermission(action = "SCR-1505.chay-test", module = "workflow", method = "POST")
  public void checkTest(AuthzResource resource) {
    // Quyết định do PermissionInterceptor thực hiện trước khi vào thân.
  }

  @Override
  @RequiresPermission(action = "SCR-1505.de-nghi-phat-hanh", module = "workflow", method = "POST")
  public void checkSubmit(AuthzResource resource) {
    // Quyết định do PermissionInterceptor thực hiện trước khi vào thân.
  }

  @Override
  @RequiresPermission(
      action = "SCR-1505.phe-duyet",
      module = "workflow",
      method = "POST",
      stepUp = true)
  public void checkApprove(AuthzResource resource) {
    // Quyết định do PermissionInterceptor thực hiện trước khi vào thân.
  }
}
