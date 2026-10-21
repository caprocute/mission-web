package vn.viettel.okrkpi.appraisal.api;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng đọc kết quả đánh giá cán bộ ({@code appraisal :: api}) của {@code MOD-04 · 360 & Appraisal}
 * mở cho {@code MOD-06 (dossier)} và {@code MOD-07 (oversight)} (TL-01 mục 2.1).
 */
public interface AppraisalResultApi {

  /**
   * Tra cứu kết quả xếp loại của cán bộ trong kỳ đánh giá.
   *
   * @param tenantId định danh tenant
   * @param periodId định danh kỳ đánh giá
   * @param personId định danh cán bộ
   * @return mã xếp loại nếu đã chốt
   */
  Optional<String> findFinalGrade(TenantId tenantId, PeriodId periodId, UUID personId);
}
