package vn.viettel.okrkpi.org.infrastructure.tenant;

import java.util.UUID;

/**
 * Tenant của yêu cầu đang xử lý, lấy từ JWT đã xác thực. Trình quản lý giao dịch đọc giá trị này để
 * {@code SET LOCAL app.tenant_id} (TL-03 "Đặt ngữ cảnh tenant an toàn qua PgBouncer"). Thay bằng thư viện nền khi
 * NEN-033 có.
 */
public final class TenantContextHolder {

  private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

  private TenantContextHolder() {
  }

  public static void set(UUID tenantId) {
    CURRENT.set(tenantId);
  }

  public static void clear() {
    CURRENT.remove();
  }

  public static UUID require() {
    UUID tenant = CURRENT.get();
    if (tenant == null) {
      throw new IllegalStateException("Thiếu ngữ cảnh tenant cho giao dịch");
    }
    return tenant;
  }
}
