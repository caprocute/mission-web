package vn.viettel.okrkpi.oversight.api;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng truy vấn giám sát và kiểm tra ({@code oversight :: api}) của {@code MOD-07 · Inspection &
 * Oversight} (TL-01 mục 2.1).
 */
public interface OversightQueryApi {

  /**
   * Kiểm tra xem hồ sơ đánh giá của cán bộ trong kỳ có đang trong diện kiểm tra/giám sát hay không.
   *
   * @param tenantId định danh tenant
   * @param periodId định danh kỳ đánh giá
   * @param personId định danh cán bộ
   * @return {@code true} nếu đang có cuộc kiểm tra mở
   */
  boolean hasActiveInspection(TenantId tenantId, PeriodId periodId, UUID personId);
}
