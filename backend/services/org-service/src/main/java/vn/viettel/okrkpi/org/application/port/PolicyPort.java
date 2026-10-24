package vn.viettel.okrkpi.org.application.port;

import vn.viettel.okrkpi.org.application.Actor;

/**
 * Cổng quyết định quyền (RBAC + ABAC). Hiện thực đích là OPA qua thư viện nền NEN-014; hiện thực tạm đọc vai trò
 * và mức AAL từ {@code x-mission} của hợp đồng. Ném {@code ForbiddenOperationException} hoặc
 * {@code StepUpRequiredException} khi từ chối.
 */
public interface PolicyPort {

  void authorize(Actor actor, String operationId);

  /**
   * Chủ thể có vai trò được làm thao tác không (để tính {@code permittedActions}); thiếu bước lên vẫn tính là được vì
   * lúc thực hiện máy chủ sẽ yêu cầu bước lên.
   */
  default boolean permits(Actor actor, String operationId) {
    try {
      authorize(actor, operationId);
      return true;
    } catch (vn.viettel.okrkpi.org.application.StepUpRequiredException e) {
      return true;
    } catch (vn.viettel.okrkpi.org.application.ForbiddenOperationException e) {
      return false;
    }
  }

  default boolean hasTenantScope(Actor actor) {
    return false;
  }
}
