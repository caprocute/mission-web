package vn.viettel.okrkpi.org.application.port;

import vn.viettel.okrkpi.org.application.Actor;

/**
 * Cổng quyết định quyền (RBAC + ABAC). Hiện thực đích là OPA qua thư viện nền NEN-014; hiện thực tạm đọc vai trò
 * và mức AAL từ {@code x-mission} của hợp đồng. Ném {@code ForbiddenOperationException} hoặc
 * {@code StepUpRequiredException} khi từ chối.
 */
public interface PolicyPort {

  void authorize(Actor actor, String operationId);
}
