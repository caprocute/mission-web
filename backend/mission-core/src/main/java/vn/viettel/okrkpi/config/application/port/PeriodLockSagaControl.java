package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.config.domain.LockCommand;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;

/**
 * Cổng gửi lệnh điều khiển (tạm dừng, tiếp tục, chạy lại hồ sơ lỗi, bù trừ) xuống saga {@code
 * CHOT_KY} của SVC-06, gọi trong giao dịch của MOD-08 sau khi đã kiểm trạng thái, bốn mắt và MFA.
 *
 * <p>{@code platform.yaml} hiện chỉ có {@code startPeriodCloseSaga}; khung saga DV-06-05 có điều
 * khiển {@code TAM_DUNG}, {@code TIEP_TUC}, {@code THU_LAI}, {@code BU_TRU} nhưng chưa công bố qua
 * HTTP. Vì vậy hiện thực mặc định ghi lệnh vào hộp lệnh {@code config.period_lock_command} cùng
 * giao dịch để saga chốt kỳ (DV-06-06, DV-06-07) nhận sau; khi hợp đồng có thao tác điều khiển,
 * thay bằng adapter gọi HTTP sau khi giao dịch commit (TL-03: không gọi REST thay đổi dữ liệu dịch
 * vụ khác trong giao dịch đang mở).
 */
@FunctionalInterface
public interface PeriodLockSagaControl {

  /**
   * Gửi một lệnh tới saga của lệnh khoá kỳ.
   *
   * @param profileIds hồ sơ cần chạy lại (chỉ với {@link LockCommand#CHAY_LAI_LOI}); rỗng là toàn
   *     bộ hàng đợi lỗi
   * @param comment ghi chú của người điều khiển, có thể trống
   * @return mã tác vụ theo dõi ở {@code /api/v1/jobs/{id}}
   */
  UUID send(
      PeriodLockOrder order,
      LockCommand command,
      List<UUID> profileIds,
      String comment,
      UUID requestedBy);
}
