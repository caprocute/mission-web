package vn.viettel.okrkpi.okr.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng lưu quyết định duyệt mục tiêu (CORE-01-06): khoá mục tiêu, đổi trạng thái mục tiêu và KR,
 * ghi {@code okr.objective_review} chỉ ghi thêm. Tên thao tác ghi tránh tiền tố của quy tắc
 * ArchUnit {@code writesCheckPeriodLock} vì kiểm khoá kỳ nằm ở dịch vụ ứng dụng.
 */
public interface OkrReviewRepository {

  /** Mục tiêu đang xét duyệt, đọc dưới khoá dòng. */
  record ReviewTarget(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID ownerUnitId,
      UUID parentObjectiveId,
      String code,
      String title,
      int tier,
      String status,
      int versionNo,
      UUID createdBy,
      String processInstanceId,
      long rowVersion) {}

  /** Một dòng {@code okr.objective_review}. */
  record ReviewRow(
      UUID id, int versionNo, UUID reviewerId, String decision, String comment, Instant at) {}

  Optional<ReviewTarget> lockTarget(TenantId tenant, UUID id);

  /** Chuyển {@code DA_DUYET}, ghi {@code approved_by/at}; trả phiên bản mới. */
  long markApproved(TenantId tenant, ReviewTarget target, UUID approver);

  /**
   * Đưa về {@code NHAP}; {@code nextRound} tăng {@code version_no} (lần trình) để lần trình sau có
   * dòng {@code objective_review} riêng. Trả phiên bản mới.
   */
  long reopenDraft(TenantId tenant, ReviewTarget target, UUID actor, boolean nextRound);

  /** Chuyển {@code DONG}; trả phiên bản mới. */
  long markClosed(TenantId tenant, ReviewTarget target, UUID actor);

  void appendReview(
      TenantId tenant, ReviewTarget target, UUID reviewer, String decision, String comment);

  /**
   * Phiên bản điều chỉnh đang mở (NHAP hoặc CHO_DUYET) của cùng mục tiêu: cùng kỳ, đơn vị, mã và
   * {@code version_no} lớn hơn.
   */
  Optional<UUID> pendingRevision(TenantId tenant, ReviewTarget target);

  /**
   * Sao chép mục tiêu thành phiên bản mới ở {@code NHAP} ({@code version_no} lớn nhất của mã cộng
   * 1), kèm căn cứ, KR (giữ mã KR) và liên kết; trả mã dòng mới.
   */
  UUID cloneAsRevision(TenantId tenant, ReviewTarget target, UUID actor);

  /** Phiên bản đã duyệt trước {@code target} của cùng mục tiêu, đọc dưới khoá dòng. */
  Optional<ReviewTarget> previousApproved(TenantId tenant, ReviewTarget target);

  /** Người soạn các mục tiêu đã duyệt nhận {@code parentId} làm mục tiêu cấp trên. */
  List<UUID> childAuthors(TenantId tenant, UUID parentId);

  /** Căn cứ của mục tiêu ({@code okr.objective_basis.basis_id}). */
  List<UUID> basisIds(TenantId tenant, int bucket, UUID objectiveId);

  /** Ý kiến duyệt của mục tiêu, mới nhất trước. */
  List<ReviewRow> reviews(TenantId tenant, UUID objectiveId);
}
