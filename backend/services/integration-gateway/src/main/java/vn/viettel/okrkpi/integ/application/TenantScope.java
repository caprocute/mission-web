package vn.viettel.okrkpi.integ.application;

import java.util.function.Supplier;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Chạy việc trong ngữ cảnh tenant để trình quản lý giao dịch đặt {@code app.tenant_id} (RLS) ở đầu
 * mỗi giao dịch.
 */
final class TenantScope {

  private TenantScope() {}

  static <T> T call(TenantId tenant, Supplier<T> work) {
    Object[] result = new Object[1];
    TenantContext.run(tenant, () -> result[0] = work.get());
    @SuppressWarnings("unchecked")
    T value = (T) result[0];
    return value;
  }
}
