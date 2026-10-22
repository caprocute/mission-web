package vn.viettel.okrkpi.workflow.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Vị trí keyset trong hộp việc: hộp việc sắp theo hạn tăng dần, việc không có hạn xếp cuối, rồi
 * theo mã task để thứ tự duy nhất (hợp đồng {@code listWorkflowInbox} "Sắp theo hạn tăng dần").
 *
 * @param dueAt hạn của dòng cuối trang trước, {@code null} khi dòng đó không có hạn
 * @param taskId mã task của dòng cuối trang trước
 */
public record InboxPosition(Instant dueAt, String taskId) {

  public InboxPosition {
    Objects.requireNonNull(taskId, "taskId");
  }

  public static InboxPosition after(TaskProjection last) {
    return new InboxPosition(last.dueAt(), last.taskId());
  }
}
