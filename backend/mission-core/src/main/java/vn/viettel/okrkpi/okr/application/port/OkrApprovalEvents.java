package vn.viettel.okrkpi.okr.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.okr.application.port.OkrAiTraceRepository.AiTrace;
import vn.viettel.okrkpi.okr.application.port.OkrReviewRepository.ReviewTarget;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Sự kiện của quyết định duyệt, ghi outbox trong cùng giao dịch (CORE-01-06). */
public interface OkrApprovalEvents {

  /** EVT-01 {@code okr.objective.approved.v1}. */
  void approved(
      TenantId tenant, ReviewTarget target, UUID approver, List<UUID> basisIds, long rowVersion);

  /**
   * EVT-01 kèm dấu vết nội dung AI của mục tiêu (CORE-01-13) để SVC-09 ghi sổ cái trường giữ
   * nguyên, đã sửa; mặc định bỏ dấu vết.
   */
  default void approved(
      TenantId tenant,
      ReviewTarget target,
      UUID approver,
      List<UUID> basisIds,
      long rowVersion,
      List<AiTrace> aiContent) {
    approved(tenant, target, approver, basisIds, rowVersion);
  }

  /** EVT-21 tới người soạn khi mục tiêu bị trả lại. */
  void returned(TenantId tenant, ReviewTarget target, UUID reviewer, long rowVersion);

  /** EVT-21 tới đơn vị sở hữu và người soạn khi mục tiêu bị huỷ ({@code cancelObjective}). */
  void cancelled(TenantId tenant, ReviewTarget target, UUID actor, long rowVersion);

  /**
   * EVT-21 tới người soạn các mục tiêu cấp dưới đã duyệt khi mục tiêu cấp trên được điều chỉnh
   * (TL-02a QT-02 "Ngoại lệ và xử lý"); không tự đổi mục tiêu cấp dưới.
   */
  void parentRevised(
      TenantId tenant, ReviewTarget revision, List<UUID> recipients, UUID actor, long rowVersion);
}
