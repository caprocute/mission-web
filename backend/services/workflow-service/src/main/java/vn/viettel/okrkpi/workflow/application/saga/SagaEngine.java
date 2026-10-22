package vn.viettel.okrkpi.workflow.application.saga;

import java.time.Duration;
import java.util.UUID;

/**
 * Cổng tới engine BPMN chạy saga (Flowable, external worker task). Mọi lệnh tham gia giao dịch hiện
 * hành, nên trạng thái {@code saga_step} và trạng thái engine được ghi cùng một giao dịch.
 */
public interface SagaEngine {

  /** Việc engine làm sau một lượt. */
  enum Next {
    /** Tạo lượt mới ngay. */
    TIEP,
    /** Dừng ở nút chờ tới khi {@link #wake} được gọi. */
    CHO,
    /** Kết thúc tiến trình. */
    XONG
  }

  /** Tạo tiến trình cho saga; trả mã tiến trình. */
  String launch(UUID sagaId, UUID tenantId, String businessKey);

  /** Đánh thức tiến trình đang ở nút chờ; không làm gì khi tiến trình chưa tới nút chờ. */
  boolean wake(UUID sagaId);

  /** Kết thúc lượt {@code turnId} và chọn việc kế tiếp. */
  void completeTurn(String turnId, Next next);

  /** Lượt {@code turnId} thất bại, engine giao lại sau {@code backoff}. */
  void failTurn(String turnId, String error, Duration backoff);
}
