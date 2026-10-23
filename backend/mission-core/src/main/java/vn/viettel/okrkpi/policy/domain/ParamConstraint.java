package vn.viettel.okrkpi.policy.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Khung một tham số mà cấp dưới được phép chỉnh ({@code policy.param_constraint}, BS-03 DC-02).
 *
 * @param tightenDirection {@code TANG} chỉ được tăng so với cấp trên, {@code GIAM} chỉ được giảm,
 *     {@code KHONG} không ràng buộc chiều (chỉ giữ trong khoảng)
 * @param allowedScopes các cấp được chỉnh ({@code KHOI}, {@code TINH}, {@code DON_VI}); rỗng là mọi
 *     cấp
 */
public record ParamConstraint(
    UUID id,
    String paramKey,
    boolean canToggle,
    boolean canChangeLevels,
    BigDecimal minValue,
    BigDecimal maxValue,
    String tightenDirection,
    List<String> allowedScopes) {

  public static final Set<String> DIRECTIONS = Set.of("TANG", "GIAM", "KHONG");
  public static final Set<String> SCOPES = Set.of("KHOI", "TINH", "DON_VI");

  public ParamConstraint {
    Objects.requireNonNull(paramKey, "paramKey");
    Objects.requireNonNull(tightenDirection, "tightenDirection");
    allowedScopes = allowedScopes == null ? List.of() : List.copyOf(allowedScopes);
  }

  /** Bản sao gắn mã dòng mới, dùng khi nhân bản phiên bản hồ sơ. */
  public ParamConstraint copyTo(UUID newId) {
    return new ParamConstraint(
        newId,
        paramKey,
        canToggle,
        canChangeLevels,
        minValue,
        maxValue,
        tightenDirection,
        allowedScopes);
  }
}
