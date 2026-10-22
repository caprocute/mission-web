package vn.viettel.okrkpi.policy.application.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Lưu yêu cầu phát hành và chữ ký phê duyệt của phiên bản Policy Pack (CORE-05-09). */
public interface PolicyPublishRepository {

  void insertRequest(TenantId tenant, PolicyPublishRequest request);

  /** Yêu cầu đang chờ phê duyệt của phiên bản. */
  Optional<PolicyPublishRequest> findPending(TenantId tenant, UUID versionId);

  /** Ghi quyết định nếu {@code row_version} của yêu cầu còn bằng {@code expected}. */
  boolean storeDecision(TenantId tenant, PolicyPublishRequest request, long expected);

  /** Ghi trạng thái và ngày hiệu lực khi gửi phê duyệt nếu {@code row_version} còn bằng. */
  boolean storeSubmitted(TenantId tenant, PolicyPackVersion version, long expected);

  /** Ghi chữ ký CAdES, chứng thư, dấu thời gian, người và lúc phê duyệt. */
  boolean storeSignature(
      TenantId tenant,
      PolicyPackVersion version,
      PolicyPackSigner.SignedHash signed,
      long expected);
}
