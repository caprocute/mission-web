package vn.viettel.okrkpi.scoring.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Một sản phẩm của nhiệm vụ trong tập đầu vào: trạng thái phiên bản minh chứng cuối, mức chất lượng
 * người nghiệm thu chọn, cờ chặn của cảnh báo trùng và số lần bị yêu cầu bổ sung (QT-07 bước 9–11).
 *
 * @param qualityLevel mức B; {@code null} khi chưa nghiệm thu
 * @param duplicateBlocked minh chứng bị cảnh báo trùng mà chưa xác nhận hợp lệ (BR-09-02)
 */
public record ProductInput(
    UUID productId,
    String status,
    BigDecimal qualityLevel,
    boolean duplicateBlocked,
    int revisionCount) {

  /** Trạng thái phiên bản minh chứng duy nhất được tính điểm (BR-07-05). */
  public static final String CONFIRMED = "DA_XAC_NHAN";

  /** Năm mức chất lượng (BR-07-07). */
  public static final Set<BigDecimal> QUALITY_LEVELS =
      Set.of(
          new BigDecimal("1"),
          new BigDecimal("0.75"),
          new BigDecimal("0.5"),
          new BigDecimal("0.25"),
          BigDecimal.ZERO);

  public ProductInput {
    Objects.requireNonNull(productId, "productId");
    Objects.requireNonNull(status, "status");
    if (qualityLevel != null) {
      qualityLevel = qualityLevel.stripTrailingZeros();
      BigDecimal q = qualityLevel;
      if (QUALITY_LEVELS.stream().noneMatch(l -> l.compareTo(q) == 0)) {
        throw new IllegalArgumentException("Mức chất lượng phải là 1; 0,75; 0,5; 0,25 hoặc 0");
      }
    }
    if (revisionCount < 0) {
      throw new IllegalArgumentException("Số lần chỉnh sửa không âm");
    }
    if (CONFIRMED.equals(status) && qualityLevel == null) {
      throw new IllegalArgumentException("Sản phẩm đã xác nhận phải có mức chất lượng");
    }
  }

  /** Được đếm vào A và B: đã xác nhận và không bị chặn trùng (BR-07-05, BR-09-02). */
  public boolean counted() {
    return CONFIRMED.equals(status) && !duplicateBlocked;
  }
}
