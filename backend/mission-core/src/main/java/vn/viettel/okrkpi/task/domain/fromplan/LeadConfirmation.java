package vn.viettel.okrkpi.task.domain.fromplan;

import java.util.UUID;

/**
 * Xác nhận dòng kế hoạch có đơn vị chủ trì khác đơn vị lập (TL-02a QT-04 "Ngoại lệ và xử lý"): đơn
 * vị chủ trì nhận việc trước khi kế hoạch được duyệt; từ chối thì dòng bị đánh lỗi khi kiểm tra kế
 * hoạch.
 */
public final class LeadConfirmation {

  public static final String CONFIRMED = "DA_XAC_NHAN";
  public static final String REJECTED = "TU_CHOI";
  public static final String RULE = "QT-04";
  public static final String PENDING_MESSAGE =
      "Dòng %d giao đơn vị khác chủ trì, đang chờ đơn vị đó xác nhận nhận việc.";
  public static final String REJECTED_MESSAGE =
      "Dòng %d bị đơn vị chủ trì từ chối nhận việc, cần sửa đơn vị chủ trì hoặc bỏ dòng.";

  /** Mức của kết quả kiểm tra. */
  public enum Issue {
    NONE,
    PENDING,
    REJECTED
  }

  private LeadConfirmation() {}

  /** Dòng cần xác nhận khi chủ trì là đơn vị khác đơn vị lập kế hoạch. */
  public static boolean required(UUID ownerUnitId, UUID leadUnitId) {
    return leadUnitId != null && !leadUnitId.equals(ownerUnitId);
  }

  /** Kết quả kiểm tra một dòng từ trạng thái xác nhận đã lưu ({@code null} là chưa xác nhận). */
  public static Issue issue(UUID ownerUnitId, UUID leadUnitId, String status) {
    if (!required(ownerUnitId, leadUnitId) || CONFIRMED.equals(status)) {
      return Issue.NONE;
    }
    return REJECTED.equals(status) ? Issue.REJECTED : Issue.PENDING;
  }

  /** Lời từ chối bắt buộc, tối đa 1.000 ký tự; trả lỗi hoặc {@code null} khi hợp lệ. */
  public static String reasonError(boolean accept, String reason) {
    if (accept) {
      return reason != null && reason.length() > 1000 ? "Ý kiến tối đa 1.000 ký tự." : null;
    }
    if (reason == null || reason.isBlank()) {
      return "Cần nêu lý do từ chối nhận việc.";
    }
    return reason.length() > 1000 ? "Lý do từ chối tối đa 1.000 ký tự." : null;
  }
}
