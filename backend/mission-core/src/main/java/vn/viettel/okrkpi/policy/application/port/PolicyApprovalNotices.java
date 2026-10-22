package vn.viettel.okrkpi.policy.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** EVT-21 cho vòng phê duyệt phát hành Policy Pack, ghi outbox trong giao dịch của bên gọi. */
public interface PolicyApprovalNotices {

  /** Báo người phê duyệt có phiên bản chờ ký. */
  void submitted(TenantId tenant, String packName, PolicyPackVersion version, List<UUID> to);

  /** Báo người soạn phiên bản bị từ chối kèm ý kiến. */
  void rejected(
      TenantId tenant, String packName, PolicyPackVersion version, String reason, List<UUID> to);
}
