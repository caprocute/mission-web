package vn.viettel.okrkpi.config.api;

import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hỏi UI Pack hiện hành của tenant có tương thích một Policy Pack hay không (CORE-08-12, ADR-25,
 * TL-01 "Quản trị phiên bản UI Pack và Policy Pack"): MOD-05 gọi trước khi gắn Policy Pack mới cho
 * kỳ và chặn khi trả {@code false} (CORE-05-10).
 */
public interface UiPackCompatibilityApi {

  /**
   * @param policyPackRef phiên bản hoặc dải Policy Pack định gắn, cùng dạng {@code
   *     requires.policyPack}
   * @return {@code true} khi mọi biểu mẫu của UI Pack hiện hành giải được trong {@code
   *     policyPackRef}, hoặc tenant chưa có UI Pack phát hành
   */
  boolean isCompatible(TenantId tenantId, String policyPackRef);
}
