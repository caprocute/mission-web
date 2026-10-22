package vn.viettel.okrkpi.task.application.plan.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PendingPlan;

/**
 * EVT-21 của kế hoạch quý: nhắc hạn duyệt (BR-04-01, CORE-02-07) và báo trả lại (CORE-02-08), ghi
 * trong giao dịch của bên gọi.
 */
public interface PlanNotices {

  /** Nhắc người lập và người duyệt trước hạn. */
  void remind(
      TenantId tenant, PendingPlan plan, String periodName, LocalDate deadline, List<UUID> to);

  /** Báo người đứng đầu đơn vị kế hoạch đã quá hạn duyệt (MSG-04-08). */
  void overdue(
      TenantId tenant, PendingPlan plan, String periodName, LocalDate deadline, List<UUID> to);

  /** Báo người lập kế hoạch bị trả lại kèm ý kiến người duyệt (QT-04 bước 7b). */
  void returned(TenantId tenant, PendingPlan plan, String periodName, UUID author);

  /** Báo người duyệt kế hoạch đã ký và duyệt xong (MSG-04-13, CORE-02-09). */
  void approved(TenantId tenant, PendingPlan plan, String periodName, UUID approver);
}
