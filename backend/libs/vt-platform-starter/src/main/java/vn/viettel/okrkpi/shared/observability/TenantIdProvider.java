package vn.viettel.okrkpi.shared.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Nguồn {@code tenant_id} cho nhật ký và comment SQL. Ngữ cảnh tenant đã kiểm (từ token, tên miền)
 * thuộc NEN-010, task đó khai báo bean thay thế; mặc định không có tenant để không bao giờ ghi một
 * giá trị chưa kiểm do client tự khai.
 */
@FunctionalInterface
public interface TenantIdProvider {

  Optional<TenantId> currentTenant(HttpServletRequest request);

  /** Mặc định khi chưa có NEN-010: không biết tenant. */
  static TenantIdProvider none() {
    return request -> Optional.empty();
  }
}
