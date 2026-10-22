package vn.viettel.okrkpi.appraisal.application.selfassessment.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.CriteriaLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Lưu phiếu tự đánh giá ({@code appraisal.self_assessment}) và tiêu chí chung tự nhận. */
public interface SelfAssessmentRepository {

  /** Phiếu phiên bản mới nhất của hồ sơ. */
  Optional<StoredSelfAssessment> current(TenantId tenant, int periodBucket, UUID profileId);

  /** Chèn phiên bản mới (lần đầu hoặc sau trả lại). */
  void insert(
      TenantId tenant, ProfileRow profile, StoredSelfAssessment draft, UUID actor, Instant at);

  /** Ghi đè nội dung bản nháp chưa nộp. */
  void update(
      TenantId tenant, int periodBucket, StoredSelfAssessment draft, UUID actor, Instant at);

  /** Thay tiêu chí chung giai đoạn {@code TU_DANH_GIA} của hồ sơ. */
  void replaceCriteria(
      TenantId tenant,
      ProfileRow profile,
      List<SelfAssessmentForm.Criterion> criteria,
      BigDecimal ratio,
      CriteriaLevel level,
      UUID actor,
      Instant at);

  /**
   * Tăng {@code row_version} của hồ sơ để {@code If-Match} lần sau thấy thay đổi; rỗng khi phiên
   * bản đã đổi từ lúc khoá.
   */
  Optional<Long> touchProfile(TenantId tenant, ProfileRow profile, UUID actor, Instant at);

  /** Đặt cờ quá hạn không tự đánh giá. */
  void flagMissing(TenantId tenant, ProfileRow profile);

  /** Chốt nội dung chờ ký: băm nội dung, thời điểm nộp; xoá yêu cầu ký cũ. */
  void markPending(
      TenantId tenant, int periodBucket, UUID selfAssessmentId, byte[] contentHash, Instant at);

  /** Gắn yêu cầu ký vừa mở ở SVC-10. */
  void attachSignRequest(
      TenantId tenant, int periodBucket, UUID selfAssessmentId, UUID signRequestId, Instant at);

  /** Bỏ trạng thái chờ ký khi mở yêu cầu ký lỗi; dữ liệu đã nhập giữ nguyên (MSG-09-05). */
  void clearPending(TenantId tenant, int periodBucket, UUID selfAssessmentId, Instant at);

  /** Ghi thời điểm ký (EVT-18). */
  void markSigned(
      TenantId tenant, int periodBucket, UUID selfAssessmentId, Instant signedAt, Instant at);

  /** Phiếu có yêu cầu ký {@code signRequestId}. */
  Optional<SignTarget> findBySignRequest(TenantId tenant, UUID signRequestId);

  /** Tóm tắt hồ sơ để đọc, không khoá dòng. */
  Optional<ProfileSummary> summary(TenantId tenant, UUID profileId);

  /**
   * Hồ sơ của {@code personId}, sắp theo mã tăng dần, sau con trỏ {@code after}.
   *
   * @param periodId lọc theo kỳ, {@code null} lấy mọi kỳ
   * @param flow lọc theo luồng, {@code null} lấy cả hai luồng
   */
  List<ProfileSummary> mine(
      TenantId tenant, UUID personId, UUID periodId, String flow, UUID after, int limit);

  /** Kết quả tiêu chí chung theo bước. */
  List<CriteriaRow> criteria(TenantId tenant, int periodBucket, UUID profileId);

  /** Mọi dòng mức của hồ sơ (cả dòng thôi hiện hành), theo thời gian. */
  List<TrailRow> trail(TenantId tenant, int periodBucket, UUID profileId);

  /** Tóm tắt hồ sơ đánh giá. */
  record ProfileSummary(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID personId,
      UUID orgUnitId,
      String flow,
      AppraisalStatus status,
      boolean head,
      boolean leadership,
      UUID currentSnapshotId,
      RatingLevel autoRating,
      RatingLevel finalRating,
      UUID appraiserId,
      UUID deciderId,
      int returnCount,
      boolean noSelfAssessment,
      Instant submittedAt,
      Instant decidedAt,
      Instant lockedAt,
      long rowVersion) {

    /** Người được đọc hồ sơ ở bước này: chủ hồ sơ, người thẩm định, người quyết định. */
    public boolean readableBy(UUID actor) {
      return actor != null
          && (actor.equals(personId) || actor.equals(appraiserId) || actor.equals(deciderId));
    }
  }

  /** Một dòng {@code general_criteria_result}. */
  record CriteriaRow(
      String stage, String code, BigDecimal ratio, CriteriaLevel level, boolean met) {}

  /** Một dòng {@code rating_proposal}. */
  record TrailRow(
      UUID id,
      LevelKind levelKind,
      RatingLevel rating,
      UUID actorId,
      String actorRole,
      boolean current,
      Instant proposedAt) {}

  /** Phiếu đã lưu: nội dung C2 ở dạng mã hoá. */
  record StoredSelfAssessment(
      UUID id,
      UUID profileId,
      int versionNo,
      String formCode,
      String formVersion,
      String structuredJson,
      String claimsJson,
      SealedText content,
      SealedText strengths,
      SealedText weaknesses,
      SealedText remedialPlan,
      SealedText breakthrough,
      SealedText reason,
      UUID dekId,
      byte[] contentHash,
      Instant submittedAt,
      UUID signRequestId,
      Instant signedAt,
      Instant updatedAt) {

    /** Đã chốt nội dung, đang chờ người ký xác nhận. */
    public boolean pending() {
      return contentHash != null && submittedAt != null && signedAt == null;
    }

    /** Đã ký xong. */
    public boolean signed() {
      return signedAt != null;
    }
  }

  /** Phiếu và hồ sơ của một yêu cầu ký. */
  record SignTarget(UUID selfAssessmentId, UUID profileId, int periodBucket, Instant signedAt) {}
}
