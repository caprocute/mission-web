package vn.viettel.okrkpi.config.api;

import java.util.Objects;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;

/**
 * Ngoại lệ nghiệp vụ ném ra khi thao tác ghi nhắm vào một kỳ đánh giá đã khóa ({@code MSG-01-06},
 * HTTP 409 Conflict).
 */
public final class PeriodLockedException extends BusinessException {

  private final PeriodId periodId;

  public PeriodLockedException(PeriodId periodId) {
    super(
        "APPRAISAL-409-PERIOD-LOCKED",
        "Kỳ đánh giá đã khoá, không thể chỉnh sửa dữ liệu: "
            + Objects.requireNonNull(periodId, "periodId must not be null").value());
    this.periodId = periodId;
  }

  public PeriodId periodId() {
    return periodId;
  }
}
