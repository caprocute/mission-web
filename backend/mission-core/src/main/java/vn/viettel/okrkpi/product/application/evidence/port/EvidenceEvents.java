package vn.viettel.okrkpi.product.application.evidence.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Sự kiện của nộp minh chứng, ghi vào {@code product.outbox_event} cùng giao dịch (CORE-03-03). */
public interface EvidenceEvents {

  /** Dữ liệu EVT-07 {@code product.evidence.submitted.v1}. */
  record Submitted(
      TenantId tenantId,
      UUID evidenceId,
      UUID versionId,
      UUID deliverableId,
      UUID taskId,
      byte[] objectSha256,
      boolean aiConfirmed,
      UUID submittedBy,
      long aggregateVersion) {}

  /** EVT-07: phiên bản minh chứng đã nộp. */
  void submitted(Submitted event);

  /** EVT-21: nhắc người nghiệm thu có minh chứng chờ xem xét. */
  void reviewRequested(
      TenantId tenantId,
      UUID reviewerId,
      String taskCode,
      UUID evidenceId,
      int versionNo,
      UUID actorId,
      long aggregateVersion);

  /**
   * EVT-21 mức ưu tiên cao: tệp của phiên bản không còn khớp bản ghi của SVC-08 khi mở nghiệm thu
   * (MSG-07-13). Ghi trong giao dịch riêng của lần mở bị chặn, không quay lui theo lỗi trả về.
   */
  void integrityFailed(
      TenantId tenantId,
      List<UUID> recipientIds,
      String taskCode,
      UUID evidenceId,
      int versionNo,
      UUID actorId,
      long aggregateVersion);
}
