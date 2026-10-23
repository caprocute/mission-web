package vn.viettel.okrkpi.appraisal.api;

import java.util.Collection;
import java.util.Map;
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

  /**
   * Hồ sơ của kỳ thuộc các đơn vị đã cho (cả kỳ khi rỗng), cho MOD-07 theo dõi tính lại, sinh lại,
   * ký lại trong phạm vi mở lại (CORE-07-09).
   */
  default Set<UUID> profilesOfUnits(TenantId tenant, PeriodId periodId, Collection<UUID> unitIds) {
    return Set.of();
  }

  /** Cán bộ và đơn vị của một hồ sơ đánh giá. */
  record ProfileSubject(UUID personId, UUID unitId) {}

  /**
   * Cán bộ và đơn vị của các hồ sơ, cho MOD-07 ẩn cảnh báo với đối tượng và lọc theo cây tổ chức
   * (BR-14-03, BR-14-08, CORE-07-11).
   */
  default Map<UUID, ProfileSubject> subjectsOf(TenantId tenant, Collection<UUID> profileIds) {
    return Map.of();
  }
}
