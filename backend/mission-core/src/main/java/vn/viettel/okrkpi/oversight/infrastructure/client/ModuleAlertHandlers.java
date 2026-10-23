package vn.viettel.okrkpi.oversight.infrastructure.client;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.oversight.application.port.AlertHandlers;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Người xử lý cảnh báo từ MOD-04 (người thẩm định của hồ sơ, của đơn vị) và cây đơn vị từ MOD-08;
 * cổng nào vắng thì trả rỗng.
 */
public class ModuleAlertHandlers implements AlertHandlers {

  private final ProfileParticipantsApi participants;
  private final AccessDirectory directory;

  public ModuleAlertHandlers(ProfileParticipantsApi participants, AccessDirectory directory) {
    this.participants = participants;
    this.directory = directory;
  }

  @Override
  public Set<UUID> ofProfiles(TenantId tenant, PeriodId period, Collection<UUID> profileIds) {
    return participants == null ? Set.of() : participants.reviewersOf(tenant, period, profileIds);
  }

  @Override
  public Set<UUID> ofUnits(TenantId tenant, PeriodId period, Collection<UUID> unitIds) {
    return participants == null ? Set.of() : participants.reviewersOfUnits(tenant, period, unitIds);
  }

  @Override
  public UUID parentOf(TenantId tenant, UUID unitId) {
    if (directory == null) {
      return null;
    }
    List<UUID> up = directory.ancestorsOf(tenant, unitId);
    return up.isEmpty() ? null : up.get(0);
  }

  @Override
  public Collection<UUID> subunitsOf(TenantId tenant, UUID unitId) {
    return directory == null ? List.of() : directory.subunitsOf(tenant, unitId);
  }
}
