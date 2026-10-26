package vn.viettel.okrkpi.appraisal.application.unitresult.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hồ sơ đánh giá kỳ quý của một đơn vị (gồm cây cấp dưới theo cây của luồng) kèm ảnh chụp điểm hiện
 * hành ({@code appraisal_profile.current_snapshot_id}) trên {@code appraisalDataSource}.
 */
public interface UnitResultRepository {

  /**
   * Tối đa {@code limit} dòng theo thứ tự {@code appraisal_profile.id}, sau con trỏ {@code
   * filter.after()} nếu có.
   */
  List<UnitResultRow> listUnitResults(TenantId tenant, Filter filter, int limit);

  /**
   * Bộ lọc; {@code flow} có thể rỗng (mọi luồng), {@code scope} là vị từ ABAC trên hồ sơ {@code p}.
   */
  record Filter(UUID unitId, UUID periodId, String flow, Scope scope, UUID after) {}

  /** Một hồ sơ; điểm, mức hệ thống và mã ảnh chụp rỗng khi chưa có ảnh chụp hiện hành. */
  record UnitResultRow(
      UUID profileId,
      UUID personId,
      String personName,
      UUID orgUnitId,
      String orgUnitName,
      String flow,
      String status,
      UUID snapshotId,
      BigDecimal totalScore,
      String autoRatingCode,
      String finalRatingCode) {}
}
