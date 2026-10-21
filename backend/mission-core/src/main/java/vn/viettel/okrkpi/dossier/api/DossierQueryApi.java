package vn.viettel.okrkpi.dossier.api;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng đọc hồ sơ đánh giá điện tử ({@code dossier :: api}) của {@code MOD-06 · Digital Dossier} mở
 * cho {@code MOD-07 (oversight)} (TL-01 mục 2.1).
 */
public interface DossierQueryApi {

  /**
   * Tra cứu định danh hồ sơ điện tử đã lập cho cán bộ trong kỳ đánh giá.
   *
   * @param tenantId định danh tenant
   * @param periodId định danh kỳ đánh giá
   * @param personId định danh cán bộ
   * @return định danh hồ sơ nếu có
   */
  Optional<UUID> findDossierId(TenantId tenantId, PeriodId periodId, UUID personId);
}
