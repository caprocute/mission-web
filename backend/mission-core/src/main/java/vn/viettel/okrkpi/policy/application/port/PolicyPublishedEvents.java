package vn.viettel.okrkpi.policy.application.port;

import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * EVT-13 {@code policy.pack.published.v1} ghi outbox trong giao dịch của bên gọi; bên nhận SVC-07,
 * SVC-06, SVC-17, SVC-09.
 */
public interface PolicyPublishedEvents {

  void published(TenantId tenant, PolicyPackVersion version);
}
