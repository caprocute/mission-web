package vn.viettel.okrkpi.config.application.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Nguồn khoá phục hồi đang hiệu lực của tổ chức (SVC-04, {@code getRecoveryKey}). */
@FunctionalInterface
public interface RecoveryKeySource {

  /** {@code id} là người nhận đại diện, {@code fingerprint} là SHA-256 SPKI dạng hex. */
  record Current(UUID id, String fingerprint) {}

  /** Rỗng khi tổ chức chưa khai báo khoá phục hồi. */
  Optional<Current> current(TenantId tenantId);
}
