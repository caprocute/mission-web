package vn.viettel.okrkpi.oversight.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Người nhận yêu cầu giải trình gửi một đơn vị (CORE-07-04): người giữ vai trò {@code manager} có
 * hiệu lực tại đơn vị ({@code config.role_binding}, suy luận wiki trang 14).
 */
public interface ComplaintPartyDirectory {

  List<UUID> holders(TenantId tenant, String roleCode, UUID orgUnitId, Instant at);
}
