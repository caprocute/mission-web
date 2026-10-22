package vn.viettel.okrkpi.analytics.infrastructure.web;

import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.viettel.okrkpi.shared.authz.OperationPolicyCatalog;

/**
 * Cổng vai trò theo {@code x-mission.roles} của thao tác trong hợp đồng (bảng {@link
 * OperationPolicyCatalog} sinh từ contracts/openapi, NEN-014), so với vai trò realm trong token.
 * Phạm vi dữ liệu (tenant, nhánh cây tổ chức) do ngữ cảnh an ninh của Cube giữ, không ở đây.
 */
final class RoleGate {

  private RoleGate() {}

  static void require(String operationId) {
    List<String> roles = OperationPolicyCatalog.getDefault().require(operationId).roles();
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    boolean ok =
        auth != null
            && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.startsWith("ROLE_") && roles.contains(a.substring(5)));
    if (!ok) {
      throw new AccessDeniedException(operationId);
    }
  }
}
