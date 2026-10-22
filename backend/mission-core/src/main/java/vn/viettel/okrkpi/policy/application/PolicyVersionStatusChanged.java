package vn.viettel.okrkpi.policy.application;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Sự kiện nội bộ của MOD-05 khi một phiên bản đổi trạng thái (phát hành, ngừng…), để bộ đệm phân
 * giải trong pod vô hiệu sau khi giao dịch ghi xong (CORE-05-03).
 */
public record PolicyVersionStatusChanged(
    TenantId tenantId, UUID packId, UUID versionId, PolicyVersionStatus status) {

  public PolicyVersionStatusChanged {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(versionId, "versionId");
    Objects.requireNonNull(status, "status");
  }
}
