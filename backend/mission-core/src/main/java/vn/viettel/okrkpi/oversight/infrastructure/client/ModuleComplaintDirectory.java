package vn.viettel.okrkpi.oversight.infrastructure.client;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi.Participants;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.api.RoleHolderApi;
import vn.viettel.okrkpi.oversight.application.port.ComplaintDirectory;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link ComplaintDirectory} từ API công khai của MOD-04 (người tham gia hồ sơ), MOD-01 (vai trò
 * theo đơn vị) và bản sao tổ chức (cây đơn vị). Cổng vắng thì danh sách rỗng, nên không ai tiếp
 * nhận được (an toàn theo hướng từ chối).
 */
public class ModuleComplaintDirectory implements ComplaintDirectory {

  private final ProfileParticipantsApi participants;
  private final RoleHolderApi roles;
  private final AccessDirectory units;

  public ModuleComplaintDirectory(
      ProfileParticipantsApi participants, RoleHolderApi roles, AccessDirectory units) {
    this.participants = participants;
    this.roles = roles;
    this.units = units;
  }

  @Override
  public Optional<Participants> participants(TenantId tenant, UUID profileId) {
    return participants == null ? Optional.empty() : participants.participantsOf(tenant, profileId);
  }

  @Override
  public List<UUID> holders(TenantId tenant, String roleCode, UUID unitId, Instant at) {
    return roles == null
        ? List.of()
        : roles.holders(tenant, roleCode, unitId, at).stream()
            .map(RoleHolderApi.Holder::personId)
            .distinct()
            .toList();
  }

  @Override
  public List<UUID> ancestorsOf(TenantId tenant, UUID unitId) {
    return units == null ? List.of() : units.ancestorsOf(tenant, unitId);
  }

  @Override
  public Set<UUID> scopeOf(TenantId tenant, UUID unitId) {
    Set<UUID> out = new LinkedHashSet<>(List.of(unitId));
    if (units != null) {
      out.addAll(units.subunitsOf(tenant, unitId));
    }
    return out;
  }
}
