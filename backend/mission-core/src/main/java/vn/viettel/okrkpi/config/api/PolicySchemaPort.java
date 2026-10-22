package vn.viettel.okrkpi.config.api;

import java.util.Optional;
import java.util.Set;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tra schema biểu mẫu của Policy Pack khi kiểm UI Pack (CORE-08-10, ADR-25, TL-01 "Quản trị phiên
 * bản UI Pack và Policy Pack"). {@code config} là mô-đun gốc nên chỉ khai báo cổng; MOD-05 hiện
 * thực (đảo phụ thuộc, CORE-05-03 hoặc task kế tiếp).
 */
public interface PolicySchemaPort {

  /**
   * Tên các trường của schema mà {@code schemaRef} trỏ tới, nếu giải được trong dải {@code
   * policyPackRange} của UI Pack.
   *
   * @param schemaRef ví dụ {@code policy://khoi-chinh-quyen/nd335/forms/mau-1b@1.2.0}
   * @return rỗng khi không giải được
   */
  Optional<Set<String>> fields(TenantId tenantId, String schemaRef, String policyPackRange);

  /**
   * Policy Pack {@code policyPackRef} đã ngừng ({@code NGUNG}) hay chưa, để chặn quay lui UI Pack
   * về phiên bản tham chiếu gói đã ngừng (CORE-08-12, MSG-18-04). Mặc định {@code false} cho tới
   * khi MOD-05 hiện thực.
   */
  default boolean retired(TenantId tenantId, String policyPackRef) {
    return false;
  }
}
