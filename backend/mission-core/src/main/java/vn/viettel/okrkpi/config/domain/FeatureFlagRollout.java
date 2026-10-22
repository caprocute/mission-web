package vn.viettel.okrkpi.config.domain;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Quy tắc bật dần của cờ ({@code config.feature_flag.rollout}, CORE-08-07, suy luận định dạng):
 * danh sách đơn vị được bật (kể cả cấp dưới, trống là toàn tenant) và tỷ lệ người dùng. Người dùng
 * rơi vào nhóm bật khi {@code CRC32("<cờ>:<người dùng>") mod 100 < percent}, nên cùng người dùng
 * luôn cho cùng kết quả.
 */
public record FeatureFlagRollout(Set<UUID> unitIds, int percent) {

  public static final FeatureFlagRollout ALL = new FeatureFlagRollout(Set.of(), 100);

  public FeatureFlagRollout {
    unitIds = unitIds == null ? Set.of() : Set.copyOf(unitIds);
    if (percent < 0 || percent > 100) {
      throw new IllegalArgumentException("Tỷ lệ bật phải trong 0..100");
    }
  }

  /**
   * @param unitAncestry đơn vị của người dùng và các đơn vị cấp trên
   */
  public boolean admits(String flagKey, UUID userId, Collection<UUID> unitAncestry) {
    if (!unitIds.isEmpty()
        && (unitAncestry == null || unitAncestry.stream().noneMatch(unitIds::contains))) {
      return false;
    }
    if (percent >= 100) {
      return true;
    }
    return userId != null && bucket(flagKey, userId) < percent;
  }

  /** Nhóm 0..99 của người dùng cho cờ. */
  public static int bucket(String flagKey, UUID userId) {
    CRC32 crc = new CRC32();
    crc.update((flagKey + ":" + userId).getBytes(StandardCharsets.UTF_8));
    return (int) (crc.getValue() % 100);
  }
}
