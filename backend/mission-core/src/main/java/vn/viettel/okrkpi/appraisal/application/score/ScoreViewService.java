package vn.viettel.okrkpi.appraisal.application.score;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.application.score.port.ScoreSnapshotReader;
import vn.viettel.okrkpi.appraisal.application.score.port.ScoreSnapshotReader.Snapshot;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Điểm và giải thích của hồ sơ theo luồng ({@code getAppraisalScores}, CORE-04-09). Người đọc là
 * chủ hồ sơ, người thẩm định, người quyết định của hồ sơ, hoặc người trong phạm vi do {@code
 * inScope} kiểm qua OPA (quản lý, thẩm định viên, người quyết định, thanh tra). {@code flow} khác
 * luồng của hồ sơ thì đọc hồ sơ cùng người, cùng kỳ ở luồng kia và kiểm quyền trên hồ sơ đó.
 */
public class ScoreViewService {

  static final String TX = "appraisalTransactionManager";

  /** Hồ sơ và ảnh chụp hiện hành được đọc. */
  public record ScoreView(ProfileSummary profile, Snapshot snapshot) {}

  private final SelfAssessmentRepository profiles;
  private final ScoreSnapshotReader snapshots;

  public ScoreViewService(SelfAssessmentRepository profiles, ScoreSnapshotReader snapshots) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
  }

  /**
   * Đọc điểm.
   *
   * @return rỗng khi không có hồ sơ (hoặc không có hồ sơ ở luồng được hỏi)
   * @throws BusinessException {@code COMMON-403-OUT-OF-SCOPE} ngoài phạm vi, {@code
   *     APPRAISAL-409-NOT-COMPUTED} khi hồ sơ chưa có ảnh chụp (MSG-09-01)
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<ScoreView> view(
      TenantId tenant, UUID profileId, String flow, UUID actor, Predicate<ProfileSummary> inScope) {
    Optional<ProfileSummary> found = profiles.summary(tenant, profileId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    ProfileSummary profile = check(found.get(), actor, inScope);
    if (flow != null && !flow.equals(profile.flow())) {
      Optional<ProfileSummary> other =
          snapshots
              .sibling(tenant, profile.periodBucket(), profile.periodId(), profile.personId(), flow)
              .flatMap(id -> profiles.summary(tenant, id));
      if (other.isEmpty()) {
        return Optional.empty();
      }
      profile = check(other.get(), actor, inScope);
    }
    Snapshot snapshot =
        snapshots
            .current(tenant, profile.periodBucket(), profile.id())
            .orElseThrow(
                () ->
                    new BusinessException(
                        "APPRAISAL-409-NOT-COMPUTED",
                        "Kết quả quý của đồng chí đang được tính, vui lòng quay lại sau."));
    return Optional.of(new ScoreView(profile, snapshot));
  }

  private static ProfileSummary check(
      ProfileSummary p, UUID actor, Predicate<ProfileSummary> inScope) {
    if (!p.readableBy(actor) && !inScope.test(p)) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền xem hồ sơ đánh giá này.");
    }
    return p;
  }
}
