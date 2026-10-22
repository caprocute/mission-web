package vn.viettel.okrkpi.shared.idempotency;

import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Chọn kho khoá và bộ quản lý giao dịch theo schema của {@link Idempotent}, cho tiến trình có một
 * DataSource và một giao dịch riêng cho từng schema (mission-core: mỗi mô-đun MOD-01..MOD-08 một
 * vai trò {@code svc_<schema>}, TL-01 "Quản lý giao dịch"). Schema không có đích riêng thì dùng
 * {@link IdempotencyStore} và {@link PlatformTransactionManager} mặc định của service.
 */
@FunctionalInterface
public interface IdempotencyTargets {

  /** Đích của schema, hoặc rỗng để dùng mặc định. */
  Optional<Target> forSchema(String schema);

  /** Kho khoá trên DataSource của schema và bộ quản lý giao dịch của cùng DataSource đó. */
  record Target(IdempotencyStore store, PlatformTransactionManager transactionManager) {
    public Target {
      Objects.requireNonNull(store, "store");
      Objects.requireNonNull(transactionManager, "transactionManager");
    }
  }
}
