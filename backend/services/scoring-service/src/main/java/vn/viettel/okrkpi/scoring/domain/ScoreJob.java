package vn.viettel.okrkpi.scoring.domain;

import java.util.Objects;
import java.util.UUID;

/** Thông tin tạo một tác vụ tính ({@code scoring.score_job}). */
public record ScoreJob(
    UUID tenantId,
    UUID id,
    UUID periodId,
    int periodBucket,
    ScoreJobKind kind,
    CanonicalDoc scope,
    UUID policyPackVersionId,
    UUID triggerEventId,
    int totalItems,
    UUID actorId) {

  public ScoreJob {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(periodId, "periodId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(policyPackVersionId, "policyPackVersionId");
    Objects.requireNonNull(actorId, "actorId");
    if (totalItems < 0) {
      throw new IllegalArgumentException("Tổng số hồ sơ không âm");
    }
  }
}
