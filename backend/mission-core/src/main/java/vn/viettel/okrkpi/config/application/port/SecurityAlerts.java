package vn.viettel.okrkpi.config.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Phát cảnh báo bảo mật cho quản trị tenant (EVT-21 qua outbox, trong giao dịch đang mở). */
public interface SecurityAlerts {

  void suspiciousAccessReported(
      TenantId tenant, UUID reportId, ActorId reporter, List<UUID> admins, Instant reportedAt);
}
