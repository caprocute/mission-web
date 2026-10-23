package vn.viettel.okrkpi.task.infrastructure.web;

import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.task.application.secret.TaskSecretContentService;
import vn.viettel.okrkpi.task.application.secret.TaskSecretContentService.SecretAuthz;

/**
 * Hỏi OPA với tài nguyên C3 có danh sách người được đọc (quy tắc {@code c3_not_recipient} của
 * {@code okrkpi.authz}); {@link TaskSecretContentService} kiểm lại cùng điều kiện ở máy chủ.
 */
@Component
public class TaskSecretGuard {

  @RequiresPermission(
      operationId = "getTaskSecretContent",
      module = "tasks",
      resourceType = TaskSecretContentService.TYPE,
      classification = "C3")
  public void read(SecretAuthz resource) {
    // Kiểm ở PermissionInterceptor.
  }

  @RequiresPermission(
      operationId = "saveTaskSecretContent",
      module = "tasks",
      resourceType = TaskSecretContentService.TYPE,
      classification = "C3")
  public void save(SecretAuthz resource) {
    // Kiểm ở PermissionInterceptor.
  }

  @RequiresPermission(
      operationId = "shareTaskSecretContent",
      module = "tasks",
      resourceType = TaskSecretContentService.TYPE,
      classification = "C3")
  public void share(SecretAuthz resource) {
    // Kiểm ở PermissionInterceptor.
  }
}
