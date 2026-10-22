package vn.viettel.okrkpi.config.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Người đang giữ một vai trò của tenant ({@code config.role_binding} còn hiệu lực). */
public interface RoleHolders {

  List<UUID> holders(TenantId tenantId, String roleCode, Instant at);
}
