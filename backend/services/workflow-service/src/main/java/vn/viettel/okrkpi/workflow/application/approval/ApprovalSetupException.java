package vn.viettel.okrkpi.workflow.application.approval;

/**
 * Không dựng được luồng duyệt: thiếu cấp duyệt, không phân giải được đơn vị trên cây tổ chức, tham
 * số sai hoặc biến vượt ngân sách. Lỗi làm huỷ giao dịch khởi tạo, không để lại tiến trình dở.
 */
public class ApprovalSetupException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ApprovalSetupException(String message) {
    super(message);
  }

  public ApprovalSetupException(String message, Throwable cause) {
    super(message, cause);
  }
}
