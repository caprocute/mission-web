package vn.viettel.okrkpi.appraisal.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Người đã thẩm định hoặc quyết định hồ sơ ({@code appraisal.rating_proposal} mức {@code
 * THAM_DINH}, {@code QUYET_DINH}), cho MOD-07 loại khỏi danh sách người duyệt mở lại kỳ (BR-13-03,
 * CORE-07-08); cán bộ và đơn vị của hồ sơ cho cảnh báo bất thường (CORE-07-11).
 */
public interface ProfileParticipantsApi {

  /** Người tham gia thẩm định, quyết định các hồ sơ đã cho trong kỳ. */
  Set<UUID> reviewersOf(TenantId tenant, PeriodId periodId, Collection<UUID> profileIds);

  /** Người tham gia thẩm định, quyết định hồ sơ của các đơn vị đã cho (hoặc cả kỳ khi rỗng). */
  Set<UUID> reviewersOfUnits(TenantId tenant, PeriodId periodId, Collection<UUID> unitIds);

  /** Cán bộ và đơn vị của một hồ sơ đánh giá. */
  record ProfileSubject(UUID personId, UUID unitId) {}

  /**
   * Cán bộ và đơn vị của các hồ sơ, cho MOD-07 ẩn cảnh báo với đối tượng và lọc theo cây tổ chức
   * (BR-14-03, BR-14-08, CORE-07-11).
   */
  default Map<UUID, ProfileSubject> subjectsOf(TenantId tenant, Collection<UUID> profileIds) {
    return Map.of();
  }

  /**
   * Những người đã tham gia một hồ sơ, theo nhóm của ràng buộc tách vai trò 21.1 (BR-12-04): người
   * nghiệm thu minh chứng ({@code product.acceptance}), người thẩm định, người đề xuất và người
   * quyết định ({@code appraisal.rating_proposal}).
   */
  record Participants(
      UUID profileId,
      UUID appraisee,
      UUID unitId,
      UUID periodId,
      Set<UUID> acceptors,
      Set<UUID> appraisers,
      Set<UUID> proposers,
      Set<UUID> deciders) {
    public Participants {
      acceptors = acceptors == null ? Set.of() : Set.copyOf(acceptors);
      appraisers = appraisers == null ? Set.of() : Set.copyOf(appraisers);
      proposers = proposers == null ? Set.of() : Set.copyOf(proposers);
      deciders = deciders == null ? Set.of() : Set.copyOf(deciders);
    }

    /** Người đã nghiệm thu, thẩm định, đề xuất hoặc quyết định hồ sơ. */
    public boolean took(UUID person) {
      return acceptors.contains(person)
          || appraisers.contains(person)
          || proposers.contains(person)
          || deciders.contains(person);
    }
  }

  /** Người tham gia của một hồ sơ (CORE-07-03, CORE-07-04); không có hồ sơ thì rỗng. */
  default Optional<Participants> participantsOf(TenantId tenant, UUID profileId) {
    return Optional.empty();
  }
}
