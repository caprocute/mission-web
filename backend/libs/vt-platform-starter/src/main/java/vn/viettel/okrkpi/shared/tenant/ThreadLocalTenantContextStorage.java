package vn.viettel.okrkpi.shared.tenant;

import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lưu tenant bằng {@code ThreadLocal} (không kế thừa sang luồng con): luồng ảo có bản sao riêng nên
 * an toàn; luồng nền tự tạo phải đặt lại ngữ cảnh bằng {@link TenantContext#run}.
 */
public final class ThreadLocalTenantContextStorage implements TenantContextStorage {

  private final ThreadLocal<TenantId> current = new ThreadLocal<>();

  @Override
  public TenantId get() {
    return current.get();
  }

  @Override
  public void set(TenantId tenant) {
    current.set(tenant);
  }

  @Override
  public void clear() {
    current.remove();
  }
}
