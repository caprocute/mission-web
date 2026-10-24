package vn.viettel.okrkpi.config.application.port;

import java.time.Instant;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.AccessChange;
import vn.viettel.okrkpi.config.domain.Delegation;
import vn.viettel.okrkpi.config.domain.RoleBinding;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-27 {@code config.access.changed.v1} và vết kiểm toán, cùng giao dịch với bản ghi. */
public interface AccessEvents {

  void roleChanged(
      TenantId tenantId, AccessChange change, RoleBinding binding, UUID actorId, Instant now);

  /**
   * Thay đổi định nghĩa vai trò tuỳ chỉnh (CORE-08-18): chỉ ghi sự kiện kiểm toán {@code
   * sys.audit.v1}; người giữ vai trò thay đổi theo EVT-27 của bản ghi cấp như cũ.
   */
  default void customRoleChanged(
      TenantId tenantId, String action, UUID roleId, int version, UUID actorId) {}

  void delegationChanged(
      TenantId tenantId, AccessChange change, Delegation delegation, UUID actorId, Instant now);
}
