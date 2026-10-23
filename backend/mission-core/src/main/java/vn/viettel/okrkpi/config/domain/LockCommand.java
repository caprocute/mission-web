package vn.viettel.okrkpi.config.domain;

/**
 * Lệnh điều khiển saga chốt kỳ gửi SVC-06 ({@code config.period_lock_command.command}), cùng tên
 * với điều khiển {@code TAM_DUNG}, {@code TIEP_TUC}, {@code BU_TRU} của khung saga DV-06-05; {@code
 * CHAY_LAI_LOI} là tạo lô mới cho hồ sơ lỗi của bước tính điểm (BR-08-11).
 */
public enum LockCommand {
  TAM_DUNG("Tạm dừng sau bước hiện tại", null),
  TIEP_TUC("Tiếp tục tiến trình khoá kỳ", null),
  CHAY_LAI_LOI("Chạy lại hồ sơ lỗi", "PERIOD_LOCK_RETRY"),
  BU_TRU("Huỷ và bù trừ toàn bộ", "PERIOD_UNLOCK");

  private final String label;
  private final String jobType;

  LockCommand(String label, String jobType) {
    this.label = label;
    this.jobType = jobType;
  }

  /** Nhãn tiếng Việt cho nhật ký sự kiện. */
  public String label() {
    return label;
  }

  /** Loại tác vụ dài ở {@code /api/v1/jobs/{id}}, {@code null} khi lệnh không trả 202. */
  public String jobType() {
    return jobType;
  }
}
