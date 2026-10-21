package vn.viettel.okrkpi.product.api;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng đọc điểm quy đổi sản phẩm ({@code product :: api}) của {@code MOD-03 · Product Catalog &
 * Scoring} mở cho {@code MOD-04 (appraisal)} (TL-01 mục 2.1).
 */
public interface ProductScoreApi {

  /**
   * Tính tổng điểm quy đổi sản phẩm của cán bộ trong kỳ đánh giá.
   *
   * @param tenantId định danh tenant
   * @param periodId định danh kỳ đánh giá
   * @param personId định danh cán bộ
   * @return tổng điểm quy đổi sản phẩm
   */
  double calculateProductScore(TenantId tenantId, PeriodId periodId, UUID personId);
}
