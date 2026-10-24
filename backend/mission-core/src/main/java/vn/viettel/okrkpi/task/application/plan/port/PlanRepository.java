package vn.viettel.okrkpi.task.application.plan.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus;

/** Bảng {@code task.plan}, {@code task.plan_version}, {@code task.plan_item} của một tenant. */
public interface PlanRepository {

  /** Dòng {@code task.plan}; {@code currentVersionId} trỏ phiên bản mới nhất. */
  record PlanRow(
      UUID id,
      UUID periodId,
      int periodBucket,
      String planType,
      UUID ownerUnitId,
      UUID ownerPersonId,
      UUID currentVersionId,
      long rowVersion) {}

  record VersionRow(
      UUID id,
      UUID planId,
      int versionNo,
      PlanVersionStatus status,
      boolean current,
      Instant submittedAt,
      Instant approvedAt,
      byte[] contentHash,
      String reviewComment,
      String changeReason,
      int itemCount,
      long rowVersion) {}

  /**
   * Người hoặc đơn vị chủ trì, phối hợp ({@code AssigneeRef}); {@code kind} là PERSON hoặc UNIT.
   */
  record Assignee(String kind, UUID id, String name) {}

  record ItemRow(
      UUID id,
      int itemNo,
      UUID catalogItemId,
      String catalogCode,
      UUID keyResultId,
      String title,
      String taskGroup,
      Assignee lead,
      List<Assignee> coAssignees,
      String productName,
      BigDecimal quantity,
      String unit,
      Integer difficulty,
      BigDecimal standardScore,
      LocalDate dueDate,
      UUID originItemId,
      String changeReason,
      String recurrence) {}

  record CatalogRow(
      UUID id,
      String code,
      String productName,
      String unit,
      int difficulty,
      BigDecimal standardScore,
      String qualityStandard,
      String evidenceRequirement) {}

  /** Dòng đã duyệt kèm kế hoạch và yêu cầu của mục danh mục (SCR-0501). */
  record ApprovedItemRow(
      UUID planId,
      UUID periodId,
      int versionNo,
      ItemRow item,
      String qualityStandard,
      String evidenceRequirement) {}

  /** Thêm kế hoạch và phiên bản 1; {@code false} khi chủ thể đã có kế hoạch trong kỳ. */
  boolean insertPlan(TenantId tenant, PlanRow plan, UUID versionId, UUID actor);

  Optional<PlanRow> findPlan(TenantId tenant, UUID planId);

  /** Kế hoạch của kỳ, lọc đơn vị và trạng thái phiên bản mới nhất; theo {@code id} tăng dần. */
  List<PlanRow> listPlans(
      TenantId tenant,
      int periodBucket,
      UUID periodId,
      UUID unitId,
      PlanVersionStatus status,
      UUID afterId,
      int limit);

  /** Mọi phiên bản, mới nhất trước. */
  List<VersionRow> versions(TenantId tenant, int periodBucket, UUID planId);

  Optional<VersionRow> lockVersion(TenantId tenant, int periodBucket, UUID planId, int versionNo);

  List<ItemRow> items(TenantId tenant, int periodBucket, UUID versionId);

  /**
   * Ghi đè dòng của phiên bản và tăng {@code row_version}.
   *
   * @return {@code row_version} mới
   */
  long replaceItems(
      TenantId tenant, PlanRow plan, VersionRow version, List<ItemRow> items, UUID actor);

  Map<UUID, CatalogRow> catalogItems(TenantId tenant, Collection<UUID> ids);

  /**
   * Phiên bản {@code DA_DUYET} hiệu lực của cùng chủ thể ở phân vùng nguồn; {@code periodId} rỗng
   * lấy kỳ có kế hoạch duyệt muộn nhất trong phân vùng.
   */
  Optional<Map.Entry<PlanRow, VersionRow>> effectiveOf(
      TenantId tenant, int periodBucket, UUID periodId, UUID ownerUnitId, UUID ownerPersonId);

  /** Dòng đã có nhiệm vụ kết thúc ({@code HOAN_THANH}, {@code HUY}). */
  Set<UUID> closedItems(TenantId tenant, int periodBucket, Collection<UUID> itemIds);

  /** KR đã có dòng ở một phiên bản của kế hoạch hoặc nhiệm vụ trong kỳ. */
  Set<UUID> linkedKeyResults(TenantId tenant, int periodBucket, UUID periodId, UUID planId);

  /**
   * Dòng của phiên bản hiệu lực ({@code DA_DUYET}) hoặc phiên bản mới nhất mang {@code status},
   * chưa sinh nhiệm vụ; theo (kế hoạch, số dòng).
   */
  List<ApprovedItemRow> approvedItems(
      TenantId tenant,
      int periodBucket,
      UUID periodId,
      PlanVersionStatus status,
      UUID afterPlanId,
      int afterItemNo,
      int limit);

  /** Kế hoạch có phiên bản 1 chưa duyệt (CORE-02-07): người lập là người tạo phiên bản 1. */
  record PendingPlan(
      UUID planId, UUID ownerUnitId, UUID versionId, UUID createdBy, long rowVersion) {}

  /** Kế hoạch của kỳ mà phiên bản 1 chưa {@code DA_DUYET}. */
  List<PendingPlan> pendingApproval(TenantId tenant, int periodBucket, UUID periodId);

  /**
   * Giữ chỗ một lần gửi thông báo theo khoá trong ngày (nhiều bản sao cùng chạy tác vụ chỉ một bản
   * gửi); {@code false} khi khoá đã có.
   */
  boolean claimNotice(TenantId tenant, String key);

  /** Phiên bản chờ duyệt kèm kế hoạch (SCR-0403 "Chờ tôi duyệt"). */
  record ReviewRow(PlanRow plan, VersionRow version) {}

  /**
   * Chuyển phiên bản sang {@code CHO_DUYET}, ghi thời điểm gửi và mã băm, xoá lý do trả lại cũ; trả
   * {@code row_version} mới.
   */
  long markSubmitted(
      TenantId tenant, PlanRow plan, VersionRow version, byte[] contentHash, UUID actor);

  /**
   * Định độ khó và điểm chuẩn của một dòng ngoài danh mục ở phiên bản {@code CHO_DUYET} (BR-04-05),
   * ghi lại mã băm nội dung để bước ký số khớp nội dung đã định điểm; trả {@code row_version} mới.
   */
  default long scoreItem(
      TenantId tenant,
      PlanRow plan,
      VersionRow version,
      UUID itemId,
      int difficulty,
      BigDecimal standardScore,
      byte[] contentHash,
      UUID actor) {
    throw new UnsupportedOperationException("scoreItem");
  }

  /** Chuyển phiên bản sang {@code TRA_LAI} kèm ý kiến; trả người lập phiên bản. */
  UUID markReturned(TenantId tenant, PlanRow plan, VersionRow version, String comment, UUID actor);

  /**
   * Phiên bản {@code CHO_DUYET} của các đơn vị, theo mã kế hoạch tăng dần sau {@code afterPlanId}.
   */
  List<ReviewRow> awaitingReview(
      TenantId tenant, java.util.Collection<UUID> unitIds, UUID afterPlanId, int limit);

  /**
   * Phiên bản {@code CHO_DUYET} của các kế hoạch có mã trong {@code planIds} (lấy từ hộp việc của
   * SVC-06), theo mã kế hoạch tăng dần sau {@code afterPlanId}.
   */
  List<ReviewRow> awaitingReviewOfPlans(
      TenantId tenant, java.util.Collection<UUID> planIds, UUID afterPlanId, int limit);

  /** Người lập và yêu cầu ký đang gắn của một phiên bản (CORE-02-09). */
  record ApprovalRow(UUID createdBy, UUID signRequestId, UUID approvedBy) {}

  Optional<ApprovalRow> approvalRow(TenantId tenant, int periodBucket, UUID versionId);

  /** Gắn yêu cầu ký vừa tạo trên SVC-10 vào phiên bản {@code CHO_DUYET}; trả row_version mới. */
  long attachSignRequest(
      TenantId tenant, PlanRow plan, VersionRow version, UUID signRequestId, UUID actor);

  /**
   * Ghi mã tiến trình {@code duyet-ke-hoach-quy} (CORE-02-08) vào phiên bản còn {@code CHO_DUYET};
   * không tăng {@code row_version} vì SVC-06 gửi lại phiên bản lúc gửi duyệt trong {@code
   * If-Match}.
   */
  boolean linkProcessInstance(
      TenantId tenant, int periodBucket, UUID versionId, String processInstanceId);

  /** Phiên bản theo mã (không biết kỳ); {@code forUpdate} khoá dòng để chuyển {@code DA_DUYET}. */
  record SignedTarget(PlanRow plan, VersionRow version, ApprovalRow approval) {}

  Optional<SignedTarget> versionById(TenantId tenant, UUID versionId, boolean forUpdate);

  /**
   * Chuyển {@code DA_DUYET} và đặt {@code is_current}, bỏ {@code is_current} của phiên bản hiệu lực
   * cũ trước để chỉ mục duy nhất một phần không bị vi phạm. Trả mã phiên bản hiệu lực cũ (nếu có)
   * và row_version mới.
   */
  record Approved(UUID supersededVersionId, long rowVersion) {}

  Approved markApproved(
      TenantId tenant, PlanRow plan, VersionRow version, UUID approver, java.time.Instant at);

  /**
   * Người duyệt đã mở yêu cầu ký này (tác vụ {@code approvePlanVersion}): {@code approved_by} khi
   * đã duyệt, ngược lại người cập nhật cuối (người gắn yêu cầu ký).
   */
  Optional<UUID> signRequestInitiator(TenantId tenant, UUID signRequestId);

  /** Dòng của phiên bản trước bị bỏ ở phiên bản điều chỉnh, kèm lý do (SCR-0403 trường 3). */
  record RemovedItem(UUID originItemId, String changeReason) {}

  /**
   * Thêm phiên bản điều chỉnh {@code NHAP} số {@code versionNo} kèm lý do chung và các dòng sao
   * chép, trỏ {@code task.plan.current_version_id} sang phiên bản mới (CORE-02-10).
   */
  default void insertAdjustment(
      TenantId tenant,
      PlanRow plan,
      UUID versionId,
      int versionNo,
      String changeReason,
      List<ItemRow> items,
      UUID actor) {
    throw new UnsupportedOperationException("insertAdjustment");
  }

  /** Dòng bị bỏ đã ghi của một phiên bản điều chỉnh. */
  default List<RemovedItem> removedItems(TenantId tenant, int periodBucket, UUID versionId) {
    return List.of();
  }

  /** Ghi đè dòng bị bỏ của phiên bản, không tăng {@code row_version}. */
  default void replaceRemovedItems(
      TenantId tenant, PlanRow plan, VersionRow version, List<RemovedItem> removed) {
    throw new UnsupportedOperationException("replaceRemovedItems");
  }
}
