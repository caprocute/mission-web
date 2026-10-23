package vn.viettel.okrkpi.workflow.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.monitor.MonitoredTask;
import vn.viettel.okrkpi.workflow.domain.monitor.WorkflowDiagram;

/**
 * Cổng ra tới engine cho ba thao tác có kiểm soát của SCR-1506 (BS-01): chuyển người xử lý, gia
 * hạn, xem sơ đồ. Không có thao tác sửa biến tiến trình hay chuyển bước tuỳ ý.
 */
public interface MonitorEngine {

  /** User task đang chạy theo mã (mã task là duy nhất trong engine), kèm tenant của nó. */
  Optional<MonitoredTask> findTask(String taskId);

  /**
   * Giao việc cho người mới, người cũ thành chủ sở hữu, ghi bình luận uỷ quyền có lưu vết.
   *
   * @return {@code REV_} của task sau khi ghi
   */
  int reassign(MonitoredTask task, UUID newAssigneeId, UUID actorId, String reason);

  /**
   * Đặt hạn mới cho user task và dời hẹn giờ hạn gắn với bước (hẹn giờ biên đến hạn từ hạn cũ trở
   * đi dời cùng khoảng), ghi bình luận gia hạn.
   *
   * @return {@code REV_} của task sau khi ghi
   */
  int extend(MonitoredTask task, Instant newDueAt, UUID actorId, String reason);

  /**
   * Sơ đồ của tiến trình gần nhất gắn với khoá nghiệp vụ.
   *
   * @param tenantId tenant cần tìm, {@code null} để tìm trong mọi tenant của cell
   */
  Optional<WorkflowDiagram> diagram(BusinessKey businessKey, UUID tenantId);
}
