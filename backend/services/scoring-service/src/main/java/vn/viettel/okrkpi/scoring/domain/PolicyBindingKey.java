package vn.viettel.okrkpi.scoring.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Khoá phân giải Policy Pack ghim cho một lần tính (DV-07-02, {@code resolvePolicyPack}): tenant,
 * kỳ, luồng, khối đối tượng và đơn vị. Khối và đơn vị có thể trống, khi đó MOD-05 khớp gắn kết
 * chung nhất của kỳ.
 */
public record PolicyBindingKey(
    UUID tenantId, UUID periodId, String flow, UUID subjectGroupId, UUID unitId) {

  static final Set<String> FLOWS = Set.of("DANG", "NHA_NUOC");

  public PolicyBindingKey {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(periodId, "periodId");
    if (flow == null || !FLOWS.contains(flow)) {
      throw new IllegalArgumentException("Luồng đánh giá không hợp lệ: " + flow);
    }
  }

  /** Khoá theo tenant, kỳ và luồng, không giới hạn khối và đơn vị. */
  public static PolicyBindingKey of(UUID tenantId, UUID periodId, String flow) {
    return new PolicyBindingKey(tenantId, periodId, flow, null, null);
  }
}
