package vn.viettel.okrkpi.task.application.plan.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PendingPlan;

/** EVT-21 nhắc hạn duyệt kế hoạch (BR-04-01, CORE-02-07), ghi trong giao dịch của bên gọi. */
public interface PlanDeadlineNotices {

  /** Nhắc người lập và người duyệt trước hạn. */
  void remind(
      TenantId tenant, PendingPlan plan, String periodName, LocalDate deadline, List<UUID> to);

  /** Báo người đứng đầu đơn vị kế hoạch đã quá hạn duyệt (MSG-04-08). */
  void overdue(
      TenantId tenant, PendingPlan plan, String periodName, LocalDate deadline, List<UUID> to);
}
