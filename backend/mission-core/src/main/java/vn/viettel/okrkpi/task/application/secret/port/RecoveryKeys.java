package vn.viettel.okrkpi.task.application.secret.port;

import java.util.Optional;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.domain.secret.SecretContentRules.RecoveryKey;

/** Khoá phục hồi tổ chức của tenant (khoá công khai, phần chia Shamir giữ ngoài máy chủ). */
@FunctionalInterface
public interface RecoveryKeys {

  Optional<RecoveryKey> of(TenantId tenant);
}
