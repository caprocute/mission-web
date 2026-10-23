package vn.viettel.okrkpi.task.application.plan.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Hộp việc duyệt kế hoạch của người gọi trên SVC-06 ({@code listWorkflowInbox} lọc {@code
 * businessType=PLAN}, đọc {@code flowable.wf_task_projection}), dùng cho SCR-0403 "Chờ tôi duyệt".
 */
public interface PlanApprovalInbox {

  /**
   * Mã các kế hoạch đang chờ người gọi duyệt; rỗng (không phải danh sách rỗng) khi không đọc được
   * hộp việc (chưa cấu hình SVC-06, không có token người dùng, SVC-06 lỗi), để bên gọi dùng nguồn
   * dự phòng.
   */
  Optional<List<UUID>> awaitingPlanIds();
}
