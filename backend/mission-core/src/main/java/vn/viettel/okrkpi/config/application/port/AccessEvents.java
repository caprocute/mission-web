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

  void delegationChanged(
      TenantId tenantId, AccessChange change, Delegation delegation, UUID actorId, Instant now);
}
