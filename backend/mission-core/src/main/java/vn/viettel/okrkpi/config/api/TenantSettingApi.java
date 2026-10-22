package vn.viettel.okrkpi.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng đọc cấu hình theo tenant của MOD-08 ({@code config.tenant_setting}, CORE-08-07), ví dụ
 * {@code csp.frame-ancestors} của TL-01 G1. Ưu tiên giá trị của đơn vị, rồi giá trị toàn tenant;
 * giá trị không khớp {@code value_schema} bị bỏ qua và ghi nhật ký.
 */
public interface TenantSettingApi {

  /**
   * @param unitId đơn vị cần cấu hình riêng; {@code null} chỉ đọc giá trị toàn tenant
   */
  Optional<JsonNode> get(TenantId tenantId, String key, UUID unitId);
}
