package vn.viettel.okrkpi.appraisal.application.scoring.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Đọc đầu vào tính điểm và ghi đánh dấu làm mới trên {@code appraisalDataSource}. */
public interface ScoringInputRepository {

  /**
   * Một trang hồ sơ của kỳ, sắp theo {@code id} tăng dần (khoá ổn định của con trỏ), chỉ cột C0,
   * C1.
   *
   * @param after id cuối của trang trước, {@code null} cho trang đầu
   */
  List<ScoringInput> page(TenantId tenant, UUID periodId, UUID after, int limit);

  /**
   * Nhận một sự kiện cho consumer; {@code false} khi đã xử lý trước đó (khử trùng lặp bằng {@code
   * appraisal.inbox_processed}).
   */
  boolean claim(TenantId tenant, String consumer, UUID eventId, String source);

  /** Đánh dấu đầu vào của các người được giao trong kỳ đã đổi; trả số hồ sơ được đánh dấu. */
  int markChanged(TenantId tenant, UUID periodId, Collection<UUID> personIds, Instant at);

  /** Khoá của một hồ sơ để đọc tiếp việc lá (CORE-04-13); rỗng khi không có trong tenant. */
  default Optional<ProfileKey> profileKey(TenantId tenant, UUID profileId) {
    return Optional.empty();
  }

  /** Hồ sơ, người, kỳ và phiên bản gói đã ghim. */
  record ProfileKey(UUID profileId, UUID personId, UUID periodId, UUID policyPackVersionId) {}

  /**
   * Đầu vào của một hồ sơ. Tiêu chí chung lấy kết quả thẩm định, chưa thẩm định thì lấy kết quả tự
   * đánh giá.
   */
  record ScoringInput(
      UUID profileId,
      UUID personId,
      String flow,
      UUID orgUnitId,
      UUID subjectGroupId,
      boolean head,
      boolean leadership,
      UUID policyPackVersionId,
      List<SelfAssessmentForm.Criterion> generalCriteria) {

    public ScoringInput {
      Objects.requireNonNull(profileId, "profileId");
      generalCriteria = List.copyOf(generalCriteria == null ? List.of() : generalCriteria);
    }
  }
}
