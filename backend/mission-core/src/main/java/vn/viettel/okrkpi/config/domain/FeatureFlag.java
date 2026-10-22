package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * Cờ tính năng của tenant (TL-03 {@code config.feature_flag}, OPS-04, CORE-08-07). Cờ do nền tảng
 * khai báo trong {@link FeatureFlagCatalog}; tenant chưa ghi lần nào thì dùng giá trị mặc định với
 * {@code rowVersion} 0 và {@code id} trống.
 */
public record FeatureFlag(
    UUID id,
    String key,
    String description,
    boolean enabled,
    FeatureFlagRollout rollout,
    Instant validFrom,
    Instant validTo,
    int rowVersion) {

  public static final int KEY_MAX = 100;

  public FeatureFlag {
    Objects.requireNonNull(key, "key");
    rollout = rollout == null ? FeatureFlagRollout.ALL : rollout;
    if (validFrom != null && validTo != null && !validFrom.isBefore(validTo)) {
      throw new IllegalArgumentException("Thời điểm bắt đầu phải trước thời điểm kết thúc");
    }
  }

  /** Đã có dòng trong {@code config.feature_flag} của tenant chưa. */
  public boolean stored() {
    return id != null;
  }

  /** Cờ có bật cho người dùng ở đơn vị này vào thời điểm {@code now} không. */
  public boolean isOn(Instant now, UUID userId, Collection<UUID> unitAncestry) {
    if (!enabled) {
      return false;
    }
    if (validFrom != null && now.isBefore(validFrom)) {
      return false;
    }
    if (validTo != null && !now.isBefore(validTo)) {
      return false;
    }
    return rollout.admits(key, userId, unitAncestry);
  }

  /** Bản đã đổi trạng thái, giữ mã dòng, phiên bản tăng một. */
  public FeatureFlag changed(
      UUID newId, boolean on, FeatureFlagRollout newRollout, Instant from, Instant to) {
    return new FeatureFlag(
        id == null ? newId : id, key, description, on, newRollout, from, to, rowVersion + 1);
  }
}
