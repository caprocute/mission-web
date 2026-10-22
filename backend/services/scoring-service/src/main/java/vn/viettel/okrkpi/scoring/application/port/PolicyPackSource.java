package vn.viettel.okrkpi.scoring.application.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;

/** Cổng đọc Policy Pack của MOD-05 qua API (SVC-07 không đọc bảng {@code policy.*}). */
public interface PolicyPackSource {

  /**
   * {@code resolvePolicyPack}: phiên bản ghim; rỗng khi MOD-05 trả {@code POLICY-404-NO-BINDING}.
   */
  Optional<PinnedPolicy> resolve(PolicyBindingKey key);

  /**
   * Manifest, quy tắc và bảng DMN của phiên bản; {@code pinned} lấy từ chi tiết phiên bản. {@code
   * version} (semver) có thể {@code null} khi chỉ biết mã phiên bản (EVT-13), khi đó nguồn tự tra.
   */
  PolicyContent load(UUID tenantId, UUID packId, UUID versionId, String version);
}
