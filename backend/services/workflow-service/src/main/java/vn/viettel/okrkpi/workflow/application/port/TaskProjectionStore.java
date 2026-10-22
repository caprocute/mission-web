package vn.viettel.okrkpi.workflow.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.InboxCounts;
import vn.viettel.okrkpi.workflow.domain.InboxFilter;
import vn.viettel.okrkpi.workflow.domain.InboxPosition;
import vn.viettel.okrkpi.workflow.domain.TaskProjection;

/**
 * Cổng ra tới {@code flowable.wf_task_projection}. Mọi thao tác ghi chạy trong giao dịch đang mở
 * của engine (TL-03: cập nhật cùng giao dịch với {@code ACT_RU_TASK}).
 */
public interface TaskProjectionStore {

  /** Chèn hoặc thay dòng chiếu của một user task. */
  void upsert(TaskProjection projection);

  /** Đổi người được giao và phiên bản sau khi engine nhận hoặc giao lại việc. */
  void reassign(UUID tenantId, String taskId, UUID assigneeId, int rowVersion);

  /** Xoá dòng chiếu khi user task hoàn thành hoặc bị huỷ. */
  void delete(UUID tenantId, String taskId);

  /**
   * Đọc một trang hộp việc theo thứ tự hạn tăng dần, việc không hạn xếp cuối, rồi mã task.
   *
   * @param after vị trí sau dòng cuối trang trước, {@code null} ở trang đầu
   * @param fetchSize số dòng cần đọc ({@code limit + 1})
   */
  List<TaskProjection> findInbox(InboxFilter filter, InboxPosition after, int fetchSize);

  /** Đếm việc đang chờ, quá hạn tại {@code now}, và đến hạn trước {@code endOfToday}. */
  InboxCounts count(InboxFilter filter, Instant now, Instant endOfToday);
}
