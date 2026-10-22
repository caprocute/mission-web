package vn.viettel.okrkpi.okr.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.okr.application.port.OkrReviewRepository.ReviewTarget;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Sự kiện của quyết định duyệt, ghi outbox trong cùng giao dịch (CORE-01-06). */
public interface OkrApprovalEvents {

  /** EVT-01 {@code okr.objective.approved.v1}. */
  void approved(
      TenantId tenant, ReviewTarget target, UUID approver, List<UUID> basisIds, long rowVersion);

  /** EVT-21 tới người soạn khi mục tiêu bị trả lại. */
  void returned(TenantId tenant, ReviewTarget target, UUID reviewer, long rowVersion);
}
