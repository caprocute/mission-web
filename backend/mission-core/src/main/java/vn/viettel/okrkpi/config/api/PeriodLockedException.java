package vn.viettel.okrkpi.config.api;

import java.util.Objects;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ErrorCatalog;
import vn.viettel.okrkpi.shared.kernel.PeriodId;

/**
 * Ghi vào dữ liệu của kỳ đang khoá hoặc đã khoá (CORE-08-03, BR-08-06, MSG-08-06), trả 409 problem
 * {@code https://okrkpi.vn/problems/period-locked} qua bộ xử lý lỗi chung. Mã lỗi theo mô-đun có
 * controller nhận lệnh ({@code TASK-409-PERIOD-LOCKED}, {@code POLICY-409-PERIOD-LOCKED}…); mô-đun
 * chưa có mã này trong danh mục, hoặc lệnh không đến từ API, thì dùng mã mặc định của MOD-04.
 */
public final class PeriodLockedException extends BusinessException {

  /** Mã lỗi mặc định khi không xác định được mô-đun gọi. */
  public static final String DEFAULT_CODE = "APPRAISAL-409-PERIOD-LOCKED";

  private final PeriodId periodId;
  private final String periodName;

  public PeriodLockedException(PeriodId periodId) {
    this(periodId, null, null);
  }

  /**
   * @param periodName tên kỳ hiển thị trong MSG-08-06 ("Quý I/2027"); trống thì dùng câu chung
   * @param module mô-đun nhận lệnh ({@code TASK}, {@code OKR}…); trống dùng mã mặc định
   */
  public PeriodLockedException(PeriodId periodId, String periodName, String module) {
    super(codeFor(module), detailOf(periodName));
    this.periodId = Objects.requireNonNull(periodId, "periodId must not be null");
    this.periodName = periodName;
  }

  /** Mã {@code <MODULE>-409-PERIOD-LOCKED} nếu danh mục lỗi có, ngược lại mã mặc định. */
  public static String codeFor(String module) {
    if (module == null || module.isBlank()) {
      return DEFAULT_CODE;
    }
    String code = module + "-409-PERIOD-LOCKED";
    return ErrorCatalog.standard().contains(code) ? code : DEFAULT_CODE;
  }

  /** MSG-08-06: "Kỳ {kỳ} đang khoá. Dữ liệu của kỳ không thể thay đổi." */
  public static String detailOf(String periodName) {
    return periodName == null || periodName.isBlank()
        ? "Kỳ đánh giá đang khoá. Dữ liệu của kỳ không thể thay đổi."
        : "Kỳ " + periodName + " đang khoá. Dữ liệu của kỳ không thể thay đổi.";
  }

  public PeriodId periodId() {
    return periodId;
  }

  public String periodName() {
    return periodName;
  }
}
