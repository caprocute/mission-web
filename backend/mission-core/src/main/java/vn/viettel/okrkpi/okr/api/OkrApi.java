package vn.viettel.okrkpi.okr.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng đọc mục tiêu và kết quả then chốt ({@code okr :: api}) của {@code MOD-01 · OKR Engine} mở
 * cho {@code MOD-02 (task)} và {@code MOD-04 (appraisal)} (TL-01 mục 2.1).
 */
public interface OkrApi {

  /**
   * Tìm thông tin tóm tắt của một kết quả then chốt (Key Result) để phục vụ gắn kết nhiệm vụ hoặc
   * tổng hợp điểm đánh giá.
   *
   * @param tenantId định danh tenant
   * @param keyResultId định danh kết quả then chốt
   * @return bản tóm tắt KR nếu tồn tại
   */
  Optional<KeyResultSummary> findKeyResultById(TenantId tenantId, UUID keyResultId);

  /**
   * Ảnh chụp tóm tắt kết quả then chốt công bố qua cổng {@link OkrApi}.
   *
   * @param id định danh KR
   * @param tenantId định danh tenant
   * @param periodId định danh kỳ đánh giá
   * @param title tiêu đề kết quả then chốt
   * @param weight trọng số KR
   * @param progressPercent tiến độ đạt được (0..100)
   */
  record KeyResultSummary(
      UUID id,
      TenantId tenantId,
      PeriodId periodId,
      String title,
      double weight,
      double progressPercent) {

    public KeyResultSummary {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(tenantId, "tenantId must not be null");
      Objects.requireNonNull(periodId, "periodId must not be null");
      Objects.requireNonNull(title, "title must not be null");
    }
  }
}
