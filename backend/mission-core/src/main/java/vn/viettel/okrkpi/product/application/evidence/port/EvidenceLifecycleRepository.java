package vn.viettel.okrkpi.product.application.evidence.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.product.domain.evidence.DeliverableRollup;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceVersion;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đọc và ghi {@code product.evidence_version}, {@code product.evidence}, {@code
 * product.deliverable} cho máy trạng thái minh chứng (CORE-03-02).
 */
public interface EvidenceLifecycleRepository {

  /** Phiên bản kèm khoá phân vùng của kỳ. */
  record LockedVersion(EvidenceVersion version, UUID periodId, int periodBucket) {}

  /** Sản phẩm của nhiệm vụ. */
  record DeliverableRow(UUID id, int periodBucket, BigDecimal quantityPlanned) {}

  /** Khoá dòng phiên bản ({@code SELECT … FOR UPDATE}) trong giao dịch đang mở. */
  Optional<LockedVersion> lockVersion(TenantId tenant, UUID versionId);

  /**
   * Ghi trạng thái và trường thay đổi của phiên bản khi {@code row_version} còn bằng {@code
   * expectedRowVersion}; trả {@code row_version} mới, hoặc rỗng khi lệch phiên bản.
   */
  Optional<Long> writeVersion(
      TenantId tenant,
      int periodBucket,
      EvidenceVersion version,
      long expectedRowVersion,
      UUID actorId,
      Instant at);

  /** Đặt {@code evidence.current_status} theo phiên bản mới nhất. */
  void markEvidenceCurrent(
      TenantId tenant,
      int periodBucket,
      UUID evidenceId,
      UUID versionId,
      String status,
      UUID actorId,
      Instant at);

  /** Khoá và đọc mọi sản phẩm của nhiệm vụ. */
  List<DeliverableRow> lockDeliverablesOfTask(TenantId tenant, UUID taskId);

  /** Phiên bản mới nhất của từng minh chứng thuộc nhiệm vụ. */
  List<EvidenceVersion> currentVersionsOfTask(TenantId tenant, UUID taskId);

  /** Ghi tổng hợp của sản phẩm khi khác giá trị đang lưu. */
  void writeDeliverableRollup(
      TenantId tenant,
      int periodBucket,
      UUID deliverableId,
      DeliverableRollup rollup,
      UUID actorId,
      Instant at);
}
