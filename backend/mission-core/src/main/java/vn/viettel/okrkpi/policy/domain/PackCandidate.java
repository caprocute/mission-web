package vn.viettel.okrkpi.policy.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Phiên bản đã phát hành hoặc đã ngừng dự tuyển khi chọn gói theo khoảng hiệu lực (TL-01 X-PP-10).
 */
public record PackCandidate(
    UUID packId,
    String code,
    PackOrigin origin,
    String flow,
    UUID versionId,
    String version,
    String contentHash,
    String bundleObjectKey,
    EffectiveRange range) {

  public PackCandidate {
    Objects.requireNonNull(packId, "packId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(versionId, "versionId");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(range, "range");
  }
}
