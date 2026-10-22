package vn.viettel.okrkpi.appraisal.application.profile.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalProfile;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.CurrentRating;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingRecorded;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Cổng lưu hồ sơ đánh giá ({@code appraisal.appraisal_profile}, {@code rating_proposal}...). */
public interface AppraisalProfileRepository {

  /** Đọc và khoá dòng hồ sơ ({@code FOR UPDATE}) cùng mức hiện hành của bốn bước. */
  Optional<ProfileRow> lock(TenantId tenant, UUID profileId);

  /**
   * Ghi trạng thái và các trường đi kèm của aggregate, tăng {@code row_version}; trả phiên bản mới.
   * Không khớp {@code expectedVersion} thì trả rỗng.
   */
  Optional<Long> save(
      TenantId tenant, ProfileRow row, AppraisalProfile profile, UUID actorId, Instant at);

  /** Đặt {@code is_current = false} cho dòng mức cũ. */
  void supersedeRating(
      TenantId tenant, int periodBucket, UUID proposalId, UUID actorId, Instant at);

  /** Chèn dòng mức mới (hiện hành) gắn với ảnh chụp điểm hiện hành của hồ sơ. */
  void insertRating(
      TenantId tenant,
      ProfileRow row,
      UUID snapshotId,
      RatingRecorded recorded,
      SealedText reason,
      SealedText comment,
      Instant at);

  /** Chèn dòng giải trình cho mức. */
  void insertExplanation(
      TenantId tenant,
      ProfileRow row,
      UUID explanationId,
      UUID proposalId,
      String explanationType,
      String autoText,
      String legalRef,
      SealedText explanation,
      UUID actorId,
      Instant at);

  /** Dòng hồ sơ đã nạp. */
  record ProfileRow(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID personId,
      String flow,
      AppraisalStatus status,
      UUID appraiserId,
      UUID deciderId,
      UUID currentSnapshotId,
      RatingLevel autoRating,
      RatingLevel finalRating,
      int returnCount,
      UUID policyPackVersionId,
      long rowVersion,
      List<CurrentRating> currentRatings) {

    public AppraisalProfile toAggregate() {
      return AppraisalProfile.restore(
          id,
          personId,
          status,
          appraiserId,
          deciderId,
          currentSnapshotId,
          autoRating,
          finalRating,
          returnCount,
          currentRatings);
    }
  }

  /** Văn bản C2 đã mã hoá phong bì; {@code null} khi không có. */
  record SealedText(byte[] cipher, UUID dekId) {}
}
