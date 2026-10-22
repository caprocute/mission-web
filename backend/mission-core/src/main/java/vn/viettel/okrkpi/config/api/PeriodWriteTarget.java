package vn.viettel.okrkpi.config.api;

import java.util.UUID;

/**
 * Đối tượng mà một lệnh ghi chạm tới, để {@link PeriodApi#assertWritable(
 * vn.viettel.okrkpi.shared.kernel.TenantId, vn.viettel.okrkpi.shared.kernel.PeriodId,
 * PeriodWriteTarget)} quyết định khi kỳ đang mở lại hậu kiểm theo phạm vi (CORE-08-06, BR-13-05):
 * đơn vị sở hữu dữ liệu và hồ sơ đánh giá chứa dữ liệu, mỗi giá trị có thể trống.
 */
public record PeriodWriteTarget(UUID unitId, UUID dossierId) {

  /** Không rõ đối tượng: chỉ ghi được khi mở lại toàn kỳ. */
  public static final PeriodWriteTarget NONE = new PeriodWriteTarget(null, null);

  public static PeriodWriteTarget unit(UUID unitId) {
    return new PeriodWriteTarget(unitId, null);
  }

  public static PeriodWriteTarget dossier(UUID dossierId) {
    return new PeriodWriteTarget(null, dossierId);
  }
}
