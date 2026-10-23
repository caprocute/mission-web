package vn.viettel.okrkpi.integ.application;

import java.util.Objects;

/** Yêu cầu gửi văn bản ra bị từ chối trước khi xếp hàng (422 ở cổng nội bộ). */
public class TransferRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Lý do trả bên gọi. */
  public enum Reason {
    /** Tenant chưa cấu hình hoặc chưa bật kết nối cho nơi nhận. */
    DESTINATION_NOT_CONFIGURED,
    /** Tổng tệp đính kèm vượt trần. */
    PAYLOAD_TOO_LARGE
  }

  private final Reason reason;

  public TransferRejectedException(Reason reason, String message) {
    super(message);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }
}
