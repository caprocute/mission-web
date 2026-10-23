package vn.viettel.okrkpi.integ.application.anchor;

import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Chạy việc trong một giao dịch ở ngữ cảnh tenant (RLS đặt {@code app.tenant_id} đầu giao dịch).
 */
final class Scoped {

  private Scoped() {}

  static <T> T tx(TenantId tenant, TransactionOperations tx, Supplier<T> work) {
    Object[] result = new Object[1];
    TenantContext.run(tenant, () -> result[0] = tx.execute(s -> work.get()));
    @SuppressWarnings("unchecked")
    T value = (T) result[0];
    return value;
  }
}
