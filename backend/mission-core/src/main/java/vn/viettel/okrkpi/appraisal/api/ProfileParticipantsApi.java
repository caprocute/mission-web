package vn.viettel.okrkpi.appraisal.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Người đã thẩm định hoặc quyết định hồ sơ ({@code appraisal.rating_proposal} mức {@code
 * THAM_DINH}, {@code QUYET_DINH}), cho MOD-07 loại khỏi danh sách người duyệt mở lại kỳ (BR-13-03,
 * CORE-07-08).
 */
public interface ProfileParticipantsApi {

  /** Người tham gia thẩm định, quyết định các hồ sơ đã cho trong kỳ. */
  Set<UUID> reviewersOf(TenantId tenant, PeriodId periodId, Collection<UUID> profileIds);

  /** Người tham gia thẩm định, quyết định hồ sơ của các đơn vị đã cho (hoặc cả kỳ khi rỗng). */
  Set<UUID> reviewersOfUnits(TenantId tenant, PeriodId periodId, Collection<UUID> unitIds);
}
