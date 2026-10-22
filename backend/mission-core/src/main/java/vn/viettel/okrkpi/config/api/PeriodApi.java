package vn.viettel.okrkpi.config.api;

import java.util.Optional;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng truy vấn kỳ đánh giá ({@code config :: api}) của {@code MOD-08 · Configuration & Period} mở
 * cho cả 7 mô-đun nghiệp vụ còn lại trong {@code mission-core} (TL-01 mục 2.1).
 */
public interface PeriodApi {

  /**
   * Tìm kỳ đánh giá đang hoạt động hiện hành của đơn vị thuê bao.
   *
   * @param tenantId định danh tenant
   * @return ảnh chụp kỳ hiện hành nếu có
   */
  Optional<PeriodSnapshot> findCurrent(TenantId tenantId);

  /**
   * Bí danh theo hợp đồng chuẩn {@code config :: api} ({@code PeriodApi.current}) trả về kỳ đánh
   * giá đang hoạt động hiện hành của đơn vị thuê bao.
   *
   * @param tenantId định danh tenant
   * @return ảnh chụp kỳ hiện hành nếu có
   */
  default Optional<PeriodSnapshot> current(TenantId tenantId) {
    return findCurrent(tenantId);
  }

  /**
   * Kỳ đánh giá hiện hành theo nhịp kỳ: kỳ chứa ngày nghiệp vụ hôm nay có loại bằng {@code cadence}
   * ({@code THANG}, {@code QUY}, {@code NAM}, {@code NAM_HOC}); {@code null} lấy ưu tiên kỳ quý.
   *
   * @param tenantId định danh tenant
   * @param cadence loại kỳ, có thể {@code null}
   * @return ảnh chụp kỳ hiện hành nếu có
   */
  default Optional<PeriodSnapshot> findCurrent(TenantId tenantId, String cadence) {
    return findCurrent(tenantId);
  }

  /**
   * Chữ ký {@code PeriodApi.current(tenant, cadence)} của TL-01 mục "Giao tiếp giữa module".
   *
   * @throws java.util.NoSuchElementException khi tenant chưa có kỳ hiện hành của nhịp này
   */
  default PeriodSnapshot current(TenantId tenantId, String cadence) {
    return findCurrent(tenantId, cadence).orElseThrow();
  }

  /**
   * Tìm kỳ đánh giá theo định danh.
   *
   * @param tenantId định danh tenant
   * @param periodId định danh kỳ đánh giá
   * @return ảnh chụp kỳ nếu tồn tại trong tenant
   */
  Optional<PeriodSnapshot> findById(TenantId tenantId, PeriodId periodId);

  /**
   * Khẳng định kỳ đánh giá đang mở cho thao tác ghi; ném {@link PeriodLockedException} nếu kỳ đã
   * khóa ({@link PeriodStatus#DA_KHOA}).
   *
   * @param tenantId định danh tenant
   * @param periodId định danh kỳ đánh giá
   * @throws PeriodLockedException khi kỳ đã khóa
   */
  void assertOpen(TenantId tenantId, PeriodId periodId) throws PeriodLockedException;

  /**
   * Khẳng định kỳ đánh giá cho phép ghi dữ liệu ({@code PeriodApi.assertWritable}); ném {@link
   * PeriodLockedException} nếu kỳ đã khóa ({@link PeriodStatus#DA_KHOA}).
   *
   * @param tenantId định danh tenant
   * @param periodId định danh kỳ đánh giá
   * @throws PeriodLockedException khi kỳ đã khóa
   */
  default void assertWritable(TenantId tenantId, PeriodId periodId) throws PeriodLockedException {
    assertOpen(tenantId, periodId);
  }

  /**
   * Như {@link #assertWritable(TenantId, PeriodId)} nhưng biết đối tượng được ghi: khi kỳ đang mở
   * lại hậu kiểm ({@code MO_LAI_HAU_KIEM}), lệnh ghi chỉ được nhận khi đối tượng nằm trong phạm vi
   * đã duyệt, ngoài phạm vi trả 409 {@code period-locked} theo MSG-13-04 (CORE-08-06, BR-13-05).
   *
   * @param target đơn vị, hồ sơ mà lệnh ghi chạm tới; {@link PeriodWriteTarget#NONE} khi không rõ
   * @throws PeriodLockedException khi kỳ khoá hoặc đối tượng ngoài phạm vi mở lại
   */
  default void assertWritable(TenantId tenantId, PeriodId periodId, PeriodWriteTarget target)
      throws PeriodLockedException {
    assertWritable(tenantId, periodId);
  }
}
