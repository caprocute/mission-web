package vn.viettel.okrkpi.scoring.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Một lô ghi tối đa {@value #MAX_SIZE} kết quả của cùng tác vụ, cùng kỳ và cùng phiên bản quy tắc
 * (TL-03: "ghi theo lô 1.000 dòng bằng COPY vào bảng tạm rồi INSERT ... SELECT").
 */
public record ScoreBatch(
    UUID tenantId,
    UUID jobId,
    ScoreJobKind jobKind,
    UUID periodId,
    int periodBucket,
    UUID policyPackVersionId,
    byte[] policyContentHash,
    String engineVersion,
    UUID actorId,
    Instant computedAt,
    List<ComputedScore> items) {

  /** Cỡ lô tối đa. */
  public static final int MAX_SIZE = 1_000;

  public ScoreBatch {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(jobId, "jobId");
    Objects.requireNonNull(jobKind, "jobKind");
    Objects.requireNonNull(periodId, "periodId");
    Objects.requireNonNull(policyPackVersionId, "policyPackVersionId");
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(computedAt, "computedAt");
    if (policyContentHash == null || policyContentHash.length != 32) {
      throw new IllegalArgumentException("Băm gói quy tắc phải là SHA-256 32 byte");
    }
    policyContentHash = policyContentHash.clone();
    if (engineVersion == null || engineVersion.isBlank() || engineVersion.length() > 32) {
      throw new IllegalArgumentException("Phiên bản bộ máy tính rỗng hoặc dài quá 32 ký tự");
    }
    int year = periodBucket / 10;
    if (year < 2000 || year > 2999 || periodBucket % 10 > 4) {
      throw new IllegalArgumentException("period_bucket không hợp lệ: " + periodBucket);
    }
    items = List.copyOf(items);
    if (items.isEmpty() || items.size() > MAX_SIZE) {
      throw new IllegalArgumentException("Lô phải có 1.." + MAX_SIZE + " kết quả");
    }
    Set<UUID> ids = new HashSet<>();
    for (ComputedScore item : items) {
      if (!ids.add(item.resultId())) {
        throw new IllegalArgumentException("Trùng mã kết quả trong lô: " + item.resultId());
      }
    }
  }

  @Override
  public byte[] policyContentHash() {
    return policyContentHash.clone();
  }

  /** Số kết quả {@link ScoreStatus#OK} và {@link ScoreStatus#LOI} của lô. */
  public int count(ScoreStatus status) {
    return (int) items.stream().filter(i -> i.status() == status).count();
  }
}
