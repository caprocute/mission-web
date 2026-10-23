package vn.viettel.okrkpi.integ.application;

import java.util.Objects;

/** Yêu cầu lần đồng bộ bị từ chối trước khi xếp hàng (409 hoặc 422 ở cổng nội bộ). */
public class SyncRunRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Lý do trả bên gọi. */
  public enum Reason {
    /** Mã kết nối không có bộ kéo theo lần chạy ở bản triển khai này. */
    CONNECTOR_NOT_RUNNABLE,
    /** Tenant chưa cấu hình hoặc chưa bật kết nối chiều vào. */
    CONNECTOR_NOT_CONFIGURED,
    /** Kết nối đang có lần chạy chờ hoặc đang chạy. */
    RUN_IN_PROGRESS,
    /** Lần chạy cần chạy lại chưa kết thúc. */
    RUN_NOT_FINISHED
  }

  private final Reason reason;

  public SyncRunRejectedException(Reason reason, String message) {
    super(message);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }
}
