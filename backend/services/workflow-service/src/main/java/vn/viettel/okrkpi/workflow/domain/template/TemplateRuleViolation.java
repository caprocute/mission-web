package vn.viettel.okrkpi.workflow.domain.template;

/** Lệnh không hợp lệ với trạng thái hiện tại của phiên bản mẫu luồng. */
public final class TemplateRuleViolation extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Lý do, để tầng ứng dụng ánh xạ sang mã lỗi của hợp đồng. */
  public enum Reason {
    /** Phiên bản không còn sửa hoặc kiểm thử được ({@code WORKFLOW-409-TEMPLATE-IMMUTABLE}). */
    BAT_BIEN,
    /** Chưa đạt kiểm thử nên chưa đề nghị phát hành được ({@code WORKFLOW-409-NOT-TESTED}). */
    CHUA_KIEM_THU,
    /** Không ở trạng thái chờ phê duyệt. */
    KHONG_CHO_PHE_DUYET,
    /** Người phê duyệt là người soạn ({@code WORKFLOW-403-SAME-AUTHOR}). */
    CUNG_NGUOI_SOAN,
    /** Thiếu chữ ký của pipeline. */
    THIEU_CHU_KY
  }

  private final Reason reason;

  public TemplateRuleViolation(Reason reason, String message) {
    super(message, null, false, false);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
