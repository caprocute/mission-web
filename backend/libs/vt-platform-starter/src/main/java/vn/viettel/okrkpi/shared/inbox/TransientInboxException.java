package vn.viettel.okrkpi.shared.inbox;

/**
 * NEN-012 · Lỗi tạm thời khi tiêu thụ sự kiện (TL-03 "Inbox idempotent"): mất kết nối CSDL, hết
 * thời gian chờ hoặc dịch vụ phụ thuộc tạm thời chưa sẵn sàng. Consumer thử lại tại chỗ tối đa 3
 * lần với độ trễ tăng dần; nếu vẫn lỗi thì đẩy sang {@code <topic>.retry.<consumer>} theo các bậc 1
 * phút, 5 phút, 30 phút.
 */
public class TransientInboxException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public TransientInboxException(String message) {
    super(message);
  }

  public TransientInboxException(String message, Throwable cause) {
    super(message, cause);
  }
}
