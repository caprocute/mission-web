package vn.viettel.okrkpi.workflow.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.InboxPosition;
import vn.viettel.okrkpi.workflow.domain.TaskProjection;
import vn.viettel.okrkpi.workflow.domain.monitor.InstanceFilter;

/**
 * Cổng đọc, ghi {@code flowable.wf_task_projection} cho giám sát tiến trình (BS-01 SCR-1506). Danh
 * sách chỉ đọc bảng chiếu, không nối {@code ACT_*}; ghi chạy trong giao dịch đang mở cùng engine.
 */
public interface InstanceMonitorStore {

  /**
   * Một trang theo hạn tăng dần (bước không hạn xếp cuối) rồi mã task.
   *
   * @param after vị trí sau dòng cuối trang trước, {@code null} ở trang đầu
   * @param fetchSize số dòng cần đọc ({@code limit + 1})
   */
  List<TaskProjection> find(InstanceFilter filter, InboxPosition after, int fetchSize);

  /** Dòng chiếu của một user task trong tenant. */
  Optional<TaskProjection> find(UUID tenantId, String taskId);

  /** Ghi hạn mới và phiên bản sau khi engine dời hạn. */
  void updateDue(UUID tenantId, String taskId, Instant dueAt, int rowVersion);

  /** Ghi người xử lý mới và phiên bản sau khi engine giao lại việc. */
  void updateAssignee(UUID tenantId, String taskId, UUID assigneeId, int rowVersion);
}
