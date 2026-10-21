package vn.viettel.okrkpi.task.api;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng đọc tiến độ nhiệm vụ ({@code task :: api}) của {@code MOD-02 · Task Management} mở cho
 * {@code MOD-04 (appraisal)} (TL-01 mục 2.1).
 */
public interface TaskSummaryApi {

  /**
   * Đếm số lượng nhiệm vụ đã hoàn thành của cán bộ trong kỳ đánh giá.
   *
   * @param tenantId định danh tenant
   * @param periodId định danh kỳ đánh giá
   * @param assigneeId định danh cán bộ thực hiện
   * @return số nhiệm vụ hoàn thành
   */
  long countCompletedTasks(TenantId tenantId, PeriodId periodId, UUID assigneeId);
}
