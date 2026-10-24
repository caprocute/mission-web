package vn.viettel.okrkpi.config.application.port;

import java.util.List;
import vn.viettel.okrkpi.config.domain.CustomRole;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đẩy định nghĩa vai trò tuỳ chỉnh của tenant sang OPA ({@code data.tenant_roles.<tenant>}, BS-04
 * ADR-34 mục 5). Lỗi kết nối không làm hỏng thao tác ghi; lượt đẩy định kỳ sẽ phủ lại.
 */
public interface TenantRolePublisher {

  void publish(TenantId tenantId, List<CustomRole> roles);
}
