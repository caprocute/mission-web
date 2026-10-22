package vn.viettel.okrkpi.config.application.port;

import java.util.UUID;
import vn.viettel.okrkpi.config.domain.PeriodReopen;

/**
 * Saga khoá lại phạm vi hẹp ở SVC-06 khi kỳ mở lại hậu kiểm hết hạn hoặc hậu kiểm xong (CORE-08-06,
 * QT-13 bước 12). Saga xong thì gọi {@code PeriodReopenService.completeRelock}. Khi cell chưa có
 * saga này, MOD-08 khoá lại ngay trong giao dịch (wiki trang 14).
 */
@FunctionalInterface
public interface PeriodRelockSaga {

  /** Khởi chạy saga, trả mã saga; idempotent theo mã yêu cầu mở lại. */
  UUID start(PeriodReopen reopen, UUID requestedBy);
}
