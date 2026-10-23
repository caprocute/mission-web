package vn.viettel.okrkpi.product.application.e2ee.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@code product.e2ee_key_wrap} với {@code object_type = EVIDENCE_FILE}, đối tượng là phiên bản.
 */
public interface EvidenceKeyWraps {

  /** Một bản bọc đã lưu; không in khoá ra nhật ký. */
  record StoredWrap(
      UUID recipientId,
      byte[] fingerprint,
      String algorithm,
      byte[] wrappedKey,
      UUID grantedBy,
      Instant createdAt,
      Instant revokedAt) {

    public boolean active() {
      return revokedAt == null;
    }

    @Override
    public String toString() {
      return "StoredWrap[recipientId=" + recipientId + "]";
    }
  }

  /** Bản bọc mới cần ghi. */
  record NewWrap(UUID recipientId, byte[] fingerprint, String algorithm, byte[] wrappedKey) {

    @Override
    public String toString() {
      return "NewWrap[recipientId=" + recipientId + "]";
    }
  }

  List<StoredWrap> of(TenantId tenant, UUID versionId);

  /** Bản bọc còn hiệu lực mới nhất của một người. */
  Optional<StoredWrap> activeFor(TenantId tenant, UUID versionId, UUID personId);

  /** Thêm hoặc thay theo vân tay khoá; bản đã thu hồi được bật lại. */
  void upsert(TenantId tenant, UUID versionId, List<NewWrap> wraps, UUID actor, Instant at);

  /** Khoá phục hồi tổ chức của tenant: vân tay hex và mã người nhận đại diện. */
  @FunctionalInterface
  interface RecoveryKeys {

    record RecoveryKey(List<String> fingerprints, UUID recipientId) {

      public RecoveryKey {
        fingerprints = List.copyOf(fingerprints);
      }
    }

    Optional<RecoveryKey> of(TenantId tenant);
  }
}
