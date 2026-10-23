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

  /**
   * Quyền mở bản mã của một tệp lưu ở SVC-08 ({@code objectId}) qua mọi phiên bản minh chứng gắn
   * tệp đó: {@code wrapped} là đã có bản bọc nào, {@code active} là người đó đang giữ bản bọc.
   */
  record FileAccess(boolean wrapped, boolean active) {}

  FileAccess fileAccess(TenantId tenant, UUID objectId, UUID personId);

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

  /**
   * Biên bản khôi phục khoá của SVC-04 ({@code getE2eeRecovery}); SVC-04 lỗi thì ném {@code
   * DependencyUnavailableException} (503).
   */
  @FunctionalInterface
  interface RecoveryRecords {

    record Recovery(String objectType, UUID objectId, UUID newRecipientId, Instant recordedAt) {}

    /** Rỗng khi SVC-04 không có biên bản này. */
    Optional<Recovery> find(TenantId tenant, UUID recoveryId);
  }
}
