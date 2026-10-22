package vn.viettel.okrkpi.task.application.fromplan.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * EVT-21 tới người lập kế hoạch sau khi sinh nhiệm vụ (MSG-04-10) và khi không huỷ được nhiệm vụ.
 */
public interface PlanTaskNotices {

  /** Số nhiệm vụ đã sinh, cập nhật, huỷ của một lần duyệt. */
  void generated(
      TenantId tenant,
      UUID planId,
      UUID versionId,
      UUID planner,
      int created,
      int updated,
      int cancelled);

  /** Dòng đã bỏ nhưng nhiệm vụ không huỷ được (có minh chứng đã xác nhận hoặc đã xong). */
  void cancelBlocked(
      TenantId tenant, UUID planId, UUID versionId, UUID planner, List<String> taskCodes);
}
