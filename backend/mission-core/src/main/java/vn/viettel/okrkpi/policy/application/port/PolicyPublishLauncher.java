package vn.viettel.okrkpi.policy.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Điểm khởi chạy saga phát hành (CORE-05-10) sau khi ký phê duyệt: trả mã tác vụ {@code
 * POLICY_PUBLISH} để bên gọi theo dõi bằng {@code GET /api/v1/jobs/{id}}.
 */
public interface PolicyPublishLauncher {

  String launch(
      TenantId tenant, UUID actor, PolicyPackVersion version, PolicyPublishRequest request);
}
