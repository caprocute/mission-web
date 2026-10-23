package vn.viettel.okrkpi.appraisal.application.score.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Cổng chỉ đọc {@code appraisal.score_snapshot} (CORE-04-09). */
public interface ScoreSnapshotReader {

  /**
   * Một ảnh chụp; các cột {@code jsonb} giữ dạng văn bản JSON.
   *
   * @param snapshotHash băm ảnh chụp, dùng làm ETag của phản hồi đọc điểm
   */
  record Snapshot(
      UUID id,
      UUID profileId,
      String flow,
      String kind,
      UUID scoreResultId,
      UUID policyPackVersionId,
      String policyVersion,
      byte[] policyContentHash,
      byte[] inputHash,
      byte[] snapshotHash,
      BigDecimal totalScore,
      BigDecimal regularScore,
      BigDecimal keyTaskScore,
      BigDecimal generalCriteriaScore,
      BigDecimal kpiScore,
      BigDecimal exceedRatio,
      BigDecimal objectiveRatio,
      String generalCriteriaLevel,
      String autoRatingCode,
      String ruleChecks,
      String downgradeReasons,
      String groupScores,
      String detail,
      Instant computedAt,
      UUID supersededById) {}

  /** Ảnh chụp hiện hành ({@code superseded_by_id IS NULL}) của hồ sơ. */
  Optional<Snapshot> current(TenantId tenant, int periodBucket, UUID profileId);

  /** Hồ sơ cùng người, cùng kỳ ở luồng {@code flow} (mỗi luồng một hồ sơ, TL-03). */
  Optional<UUID> sibling(
      TenantId tenant, int periodBucket, UUID periodId, UUID personId, String flow);
}
