package vn.viettel.okrkpi.appraisal.application.decision.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Cổng lưu lô quyết định ({@code appraisal.batch_decision}) và các truy vấn kiểm tra của lô. */
public interface BatchDecisionRepository {

  /** Lô cùng số quyết định trong năm {@code year} của tenant (mọi trạng thái). */
  Optional<BatchRow> findByNumber(TenantId tenant, int year, String decisionNo);

  /** Đọc lô; {@code lock} thì khoá dòng ({@code FOR UPDATE}). */
  Optional<BatchRow> find(TenantId tenant, UUID id, boolean lock);

  /** Trang lô của người quyết định, mới nhất trước, sau con trỏ {@code after} (mã lô). */
  List<BatchRow> page(
      TenantId tenant,
      UUID decider,
      UUID periodId,
      String flow,
      Set<String> statuses,
      UUID after,
      int limit);

  /**
   * Chèn lô trạng thái {@code NHAP}; trùng số quyết định trong kỳ ném {@code
   * DuplicateKeyException}.
   */
  void insert(TenantId tenant, NewBatch batch);

  /** Gắn yêu cầu ký vào lô và các dòng mức hiện hành của lô, lô chuyển {@code CHO_KY}. */
  void attachSignRequest(
      TenantId tenant, int bucket, UUID id, UUID signRequestId, UUID actor, Instant at);

  /** Đổi trạng thái lô, tăng {@code row_version}. */
  void setStatus(TenantId tenant, int bucket, UUID id, String status, UUID actor, Instant at);

  /** Thôi hiện hành các dòng mức chưa ký của lô; trả mã hồ sơ bị ảnh hưởng. */
  List<UUID> supersedeUnsigned(TenantId tenant, int bucket, UUID id, UUID actor, Instant at);

  /** Tăng {@code row_version} của các hồ sơ (để {@code If-Match} cũ bị từ chối). */
  void touchProfiles(
      TenantId tenant, int bucket, Collection<UUID> profileIds, UUID actor, Instant at);

  /** Thông tin kiểm tra của từng hồ sơ trong lô. */
  Map<UUID, ProfileFacts> facts(TenantId tenant, int bucket, Collection<UUID> profileIds);

  /** Mã các hồ sơ trong {@code profileIds} không thoả phạm vi ABAC {@code scope}. */
  Set<UUID> outOfScope(TenantId tenant, int bucket, Collection<UUID> profileIds, Scope scope);

  /**
   * Số hồ sơ cùng kỳ, cùng luồng ở các đơn vị trực thuộc (độ sâu ≥ 1 trên cây của luồng) của {@code
   * orgUnitId} chưa có mức {@code QUYET_DINH} hiện hành và không nằm trong {@code inBatch}.
   */
  int undecidedSubordinates(
      TenantId tenant,
      int bucket,
      UUID periodId,
      String flow,
      UUID orgUnitId,
      Collection<UUID> inBatch);

  /** Các dòng mức hiện hành của lô (tài liệu ký). */
  List<BatchRating> ratings(TenantId tenant, int bucket, UUID id);

  /** Ghi chữ ký của một dòng mức (EVT-18). */
  void markSigned(
      TenantId tenant,
      int bucket,
      UUID proposalId,
      UUID signRequestId,
      Instant signedAt,
      Instant at);

  /**
   * Hồ sơ {@code DA_KY_SO} quyết định trước {@code decidedBefore}, sắp theo mã sau con trỏ {@code
   * after}: ứng viên khoá khi hết hạn khiếu nại.
   */
  List<LockCandidate> signedProfiles(TenantId tenant, Instant decidedBefore, UUID after, int limit);

  /** Số dòng mức của lô đã ký. */
  int signedCount(TenantId tenant, int bucket, UUID id);

  /** Lô đã lưu, kèm tên người quyết định. */
  record BatchRow(
      UUID id,
      UUID periodId,
      int periodBucket,
      String flow,
      String decisionNo,
      LocalDate decisionDate,
      UUID deciderId,
      String deciderName,
      int profileCount,
      byte[] profilesHash,
      UUID signRequestId,
      String status,
      long rowVersion,
      Instant updatedAt) {}

  /** Lô mới. */
  record NewBatch(
      UUID id,
      UUID periodId,
      int periodBucket,
      String flow,
      String decisionNo,
      LocalDate decisionDate,
      UUID deciderId,
      int profileCount,
      byte[] profilesHash,
      Instant at) {}

  /**
   * Hồ sơ trong lô: đơn vị, cờ người đứng đầu và lô khác đang giữ mức quyết định hiện hành của hồ
   * sơ (lô {@code NHAP} hoặc {@code CHO_KY}).
   */
  record ProfileFacts(UUID profileId, UUID orgUnitId, boolean head, UUID pendingBatchId) {}

  /** Hồ sơ đã ký số chờ hết hạn khiếu nại. */
  record LockCandidate(UUID profileId, Instant decidedAt, UUID policyPackVersionId) {}

  /** Một dòng mức của lô. */
  record BatchRating(UUID proposalId, UUID profileId, String ratingCode, Instant signedAt) {}
}
