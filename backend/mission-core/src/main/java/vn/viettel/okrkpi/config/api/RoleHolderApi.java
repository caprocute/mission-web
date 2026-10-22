package vn.viettel.okrkpi.config.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Người đang giữ một vai trò tại một đơn vị ({@code config.role_binding} hiệu lực), cho MOD-07 chọn
 * người duyệt mở lại kỳ (CORE-07-08). Gắn ở đơn vị cấp trên với phạm vi {@code DON_VI_VA_CAP_DUOI}
 * cũng tính cho đơn vị cấp dưới, nên bên gọi tự đi lên cây đơn vị.
 */
public interface RoleHolderApi {

  /**
   * Người giữ {@code roleCode} gắn đúng tại {@code orgUnitId}, đã duyệt, còn hiệu lực lúc {@code
   * at}.
   */
  List<Holder> holders(TenantId tenant, String roleCode, UUID orgUnitId, Instant at);

  /** {@code coversSubunits}: gắn với phạm vi đơn vị và cấp dưới. */
  record Holder(UUID personId, boolean coversSubunits) {}
}
