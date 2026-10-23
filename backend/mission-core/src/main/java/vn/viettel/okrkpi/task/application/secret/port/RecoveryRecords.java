package vn.viettel.okrkpi.task.application.secret.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Biên bản khôi phục khoá của SVC-04 ({@code getE2eeRecovery}) để đối chiếu khoá bọc khôi phục
 * (CORE-07-06). SVC-04 lỗi thì ném {@code DependencyUnavailableException} (503).
 */
@FunctionalInterface
public interface RecoveryRecords {

  record Recovery(
      String objectType,
      UUID objectId,
      UUID newRecipientId,
      String newKeyFingerprint,
      Instant recordedAt) {}

  /** Rỗng khi SVC-04 không có biên bản này. */
  Optional<Recovery> find(TenantId tenant, UUID recoveryId);
}
