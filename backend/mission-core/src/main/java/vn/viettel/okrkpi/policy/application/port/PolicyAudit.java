package vn.viettel.okrkpi.policy.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Ghi vết thay đổi Policy Pack vào outbox của schema {@code policy} cùng giao dịch. */
public interface PolicyAudit {

  void changed(
      TenantId tenantId,
      UUID actorId,
      String action,
      String objectType,
      String objectId,
      UUID aggregateId,
      long version);
}
