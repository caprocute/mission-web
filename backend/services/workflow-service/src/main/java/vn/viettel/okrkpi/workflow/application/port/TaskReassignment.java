package vn.viettel.okrkpi.workflow.application.port;

import java.util.Optional;
import java.util.UUID;

/** Cổng ra tới engine để chuyển người duyệt của một user task (uỷ quyền có lưu vết). */
public interface TaskReassignment {

  /** User task đang chạy của tenant; rỗng khi không có hoặc thuộc tenant khác. */
  Optional<ReassignableTask> find(UUID tenantId, String taskId);

  /**
   * Giao việc cho người mới, giữ người cũ làm chủ sở hữu ({@code owner}) và ghi vết: bình luận loại
   * {@code UY_QUYEN} trong lịch sử, biến cục bộ {@code uyQuyenTu}, {@code uyQuyenLyDo} của task.
   */
  void reassign(ReassignableTask task, UUID newApproverId, UUID actorId, String reason);

  /**
   * User task có thể chuyển người duyệt.
   *
   * @param taskId mã user task
   * @param tenantId tenant
   * @param processInstanceId tiến trình
   * @param currentAssigneeId người đang được giao, {@code null} khi việc còn ở nhóm ứng viên
   * @param delegationAllowed tham số {@code allowDelegation} của mẫu cho tenant
   */
  record ReassignableTask(
      String taskId,
      UUID tenantId,
      String processInstanceId,
      UUID currentAssigneeId,
      boolean delegationAllowed) {}
}
