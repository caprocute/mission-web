package vn.viettel.okrkpi.product.application.evidence.port;

import java.math.BigDecimal;
import java.time.Instant;
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

  /**
   * Người nộp xác nhận các ô do AI điền (CORE-03-05, BR-07-09): ghi sổ cái qua {@code sys.audit.v1}
   * kèm ô giữ nguyên và ô đã sửa.
   */
  record AiFieldsConfirmed(
      TenantId tenantId,
      UUID versionId,
      UUID aiSuggestionId,
      List<String> kept,
      List<String> edited,
      UUID actorId,
      long aggregateVersion) {}

  void aiFieldsConfirmed(AiFieldsConfirmed event);

  /**
   * Đã kiểm chữ ký số gốc của văn bản vOffice gắn làm minh chứng (CORE-03-04, BR-07-13): ghi sổ cái
   * qua {@code sys.audit.v1} trong cùng giao dịch tạo phiên bản.
   */
  record ExternalDocVerified(
      TenantId tenantId,
      UUID versionId,
      String ref,
      UUID objectId,
      String signerName,
      UUID actorId,
      long aggregateVersion) {}

  void externalDocVerified(ExternalDocVerified event);

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

  /** Dữ liệu EVT-08 {@code product.evidence.accepted.v1}. */
  record Accepted(
      TenantId tenantId,
      UUID evidenceId,
      UUID versionId,
      UUID taskId,
      BigDecimal qualityLevel,
      UUID acceptedBy,
      Instant acceptedAt,
      long aggregateVersion) {}

  /** EVT-08: phiên bản minh chứng đã được xác nhận nghiệm thu. */
  void accepted(Accepted event);

  /**
   * EVT-21: báo người nộp minh chứng bị yêu cầu bổ sung hoặc bị từ chối; không mang nhận xét (C2),
   * người nộp xem nhận xét trên màn hình.
   */
  void returned(
      TenantId tenantId,
      UUID submitterId,
      boolean rejected,
      String taskCode,
      UUID evidenceId,
      int versionNo,
      UUID actorId,
      long aggregateVersion);
}
