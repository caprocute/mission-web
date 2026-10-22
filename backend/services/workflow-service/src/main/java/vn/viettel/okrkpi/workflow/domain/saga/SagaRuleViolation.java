package vn.viettel.okrkpi.workflow.domain.saga;

/** Lệnh không hợp lệ với trạng thái hiện tại của saga. */
public final class SagaRuleViolation extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Lý do vi phạm, để tầng ứng dụng ánh xạ sang mã lỗi của hợp đồng. */
  public enum Reason {
    DA_KET_THUC,
    DA_QUA_DIEM_KHONG_QUAY_LAI,
    KHONG_O_TRANG_THAI_LOI,
    KHONG_TAM_DUNG
  }

  private final Reason reason;

  public SagaRuleViolation(Reason reason, String message) {
    super(message, null, false, false);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
