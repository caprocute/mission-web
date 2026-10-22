package vn.viettel.okrkpi.oversight.application.port;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Người xử lý cảnh báo trong phạm vi (AC-07 {@code appraiser}) và cây đơn vị cho việc gộp. */
public interface AlertHandlers {

  /** Người thẩm định của các hồ sơ đánh giá. */
  Set<UUID> ofProfiles(TenantId tenant, PeriodId period, Collection<UUID> profileIds);

  /** Người thẩm định phụ trách các đơn vị. */
  Set<UUID> ofUnits(TenantId tenant, PeriodId period, Collection<UUID> unitIds);

  /** Đơn vị cấp trên trực tiếp; {@code null} khi là gốc hoặc chưa biết. */
  UUID parentOf(TenantId tenant, UUID unitId);

  /** Mọi đơn vị cấp dưới, không gồm chính nó. */
  Collection<UUID> subunitsOf(TenantId tenant, UUID unitId);
}
