package vn.viettel.okrkpi.workflow.application.port;

/**
 * Chạy việc nền của lô sau khi giao dịch tạo tác vụ đã cam kết, trên executor có hàng đợi giới hạn,
 * mang theo ngữ cảnh bảo mật và tenant của người gửi.
 */
public interface BatchDispatcher {

  /**
   * Lên lịch {@code work} sau khi giao dịch hiện tại cam kết (chạy ngay khi không có giao dịch).
   *
   * @throws java.util.concurrent.RejectedExecutionException khi hàng đợi đầy
   */
  void dispatchAfterCommit(Runnable work);
}
