package vn.viettel.okrkpi.oversight.infrastructure.client;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.RoleHolderApi;
import vn.viettel.okrkpi.oversight.application.port.ApproverDirectory;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Người duyệt mở lại kỳ từ API công khai của MOD-01 (vai trò theo đơn vị, cây đơn vị) và MOD-04
 * (người đã thẩm định, quyết định hồ sơ). Thiếu bean cây đơn vị thì không nâng lên cấp trên.
 */
public class ModuleApproverDirectory implements ApproverDirectory {

  private final RoleHolderApi roles;
  private final AccessDirectory units;
  private final ProfileParticipantsApi participants;

  public ModuleApproverDirectory(
      RoleHolderApi roles, AccessDirectory units, ProfileParticipantsApi participants) {
    this.roles = Objects.requireNonNull(roles, "roles");
    this.units = units;
    this.participants = participants;
  }

  @Override
  public List<UUID> holders(TenantId tenant, String roleCode, UUID orgUnitId, Instant at) {
    return roles.holders(tenant, roleCode, orgUnitId, at).stream()
        .map(RoleHolderApi.Holder::personId)
        .distinct()
        .toList();
  }

  @Override
  public List<UUID> ancestorsOf(TenantId tenant, UUID orgUnitId) {
    return units == null ? List.of() : units.ancestorsOf(tenant, orgUnitId);
  }

  @Override
  public Set<UUID> participants(
      TenantId tenant, UUID periodId, Collection<UUID> profileIds, Collection<UUID> unitIds) {
    if (participants == null) {
      return Set.of();
    }
    PeriodId period = PeriodId.of(periodId);
    Set<UUID> out = new HashSet<>();
    if (!profileIds.isEmpty()) {
      out.addAll(participants.reviewersOf(tenant, period, profileIds));
    }
    if (!unitIds.isEmpty() || profileIds.isEmpty()) {
      // Rỗng cả hai là phạm vi toàn kỳ.
      out.addAll(participants.reviewersOfUnits(tenant, period, unitIds));
    }
    return out;
  }
}
