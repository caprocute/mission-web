package vn.viettel.okrkpi.product.application.evidence.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.product.domain.evidence.CriterionResult;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Bảng {@code product.acceptance} (CORE-03-07). */
public interface AcceptanceRepository {

  /** Nhận xét đã mã hoá phong bì ({@code comment_enc}, {@code dek_id}). */
  record SealedComment(byte[] cipher, UUID dekId) {}

  /** Dòng nghiệm thu mới. */
  record NewAcceptance(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID versionId,
      UUID taskId,
      UUID reviewerId,
      String decision,
      BigDecimal qualityLevel,
      String revisionCause,
      boolean objectiveRevision,
      int revisionCount,
      List<CriterionResult> criteria,
      SealedComment comment,
      Instant decidedAt) {}

  void insertAcceptance(TenantId tenant, NewAcceptance a);

  /** Mã tiêu chuẩn đã đánh giá ở các phiên bản trước {@code versionNo} của minh chứng. */
  Set<String> priorCriteria(TenantId tenant, int periodBucket, UUID evidenceId, int versionNo);

  /** Số phiên bản trước {@code versionNo} của minh chứng bị yêu cầu bổ sung. */
  int priorRevisions(TenantId tenant, int periodBucket, UUID evidenceId, int versionNo);
}
