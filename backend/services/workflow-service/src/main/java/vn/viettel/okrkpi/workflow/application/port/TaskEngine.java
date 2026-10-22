package vn.viettel.okrkpi.workflow.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.Approver;
import vn.viettel.okrkpi.workflow.domain.Decision;
import vn.viettel.okrkpi.workflow.domain.OpenTask;

/** Cổng ra tới engine Flowable cho thao tác trên user task. */
public interface TaskEngine {

  /** User task đang chạy của tenant, rỗng khi không có hoặc thuộc tenant khác. */
  Optional<OpenTask> findOpen(UUID tenantId, String taskId);

  /** Đúng khi user task của tenant đã từng tồn tại và đã kết thúc. */
  boolean wasCompleted(UUID tenantId, String taskId);

  /**
   * Nhận việc (nếu chưa giao) và hoàn thành user task trong giao dịch đang mở: đặt biến {@code
   * decision} cho tiến trình, lưu quyết định và ý kiến ở biến cục bộ của task để dựng lịch sử.
   *
   * @throws vn.viettel.okrkpi.shared.error.VersionConflictException khi engine phát hiện xung đột
   *     khoá lạc quan
   */
  Completion complete(OpenTask task, Approver approver, Decision decision, String comment);

  /**
   * Kết quả hoàn thành.
   *
   * @param completedAt thời điểm hoàn thành
   * @param processEnded tiến trình kết thúc sau bước này
   */
  record Completion(Instant completedAt, boolean processEnded) {}
}
