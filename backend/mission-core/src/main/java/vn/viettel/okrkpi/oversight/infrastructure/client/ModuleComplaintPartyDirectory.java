package vn.viettel.okrkpi.oversight.infrastructure.client;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.config.api.RoleHolderApi;
import vn.viettel.okrkpi.oversight.application.port.ComplaintPartyDirectory;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Người nhận yêu cầu giải trình gửi đơn vị, đọc gán vai trò qua {@link RoleHolderApi}. */
public class ModuleComplaintPartyDirectory implements ComplaintPartyDirectory {

  private final RoleHolderApi holders;

  public ModuleComplaintPartyDirectory(RoleHolderApi holders) {
    this.holders = Objects.requireNonNull(holders, "holders");
  }

  @Override
  public List<UUID> holders(TenantId tenant, String roleCode, UUID orgUnitId, Instant at) {
    return holders.holders(tenant, roleCode, orgUnitId, at).stream()
        .map(RoleHolderApi.Holder::personId)
        .distinct()
        .toList();
  }
}
