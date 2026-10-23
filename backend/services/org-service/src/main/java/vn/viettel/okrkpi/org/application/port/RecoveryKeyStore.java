package vn.viettel.okrkpi.org.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Adapter {@code org.recovery_key} và {@code org.e2ee_recovery_log} (phục hồi tổ chức C3) trong tenant. */
public interface RecoveryKeyStore {

  /** Khoá đang hiệu lực, khoá dòng để thay khoá tuần tự. */
  Optional<StoredRecoveryKey> active(boolean lock);

  Optional<StoredRecoveryKey> find(UUID id);

  boolean fingerprintExists(byte[] fingerprint);

  void insert(StoredRecoveryKey key, UUID actor);

  /** Chuyển khoá sang {@code THU_HOI} kể từ {@code at}. */
  void retire(UUID id, UUID actor, Instant at);

  void insertLog(StoredRecovery log, UUID actor);

  /** Nhật ký mới nhất trước; {@code after} là (thời điểm ghi, mã) của dòng cuối trang trước. */
  List<StoredRecovery> logs(UUID objectId, Instant afterAt, UUID afterId, int limit);

  record StoredRecoveryKey(UUID id, String keyAlg, byte[] spki, byte[] fingerprint, int threshold,
      List<UUID> custodianIds, String ceremonyRef, String status, Instant validFrom, Instant validTo,
      int sigKeyVersion, byte[] signature, Instant signedAt, int rowVersion) {
  }

  record StoredRecovery(UUID id, UUID recoveryKeyId, String objectType, UUID objectId, UUID newRecipientId,
      byte[] newKeyFingerprint, List<UUID> custodianIds, String reason, String ceremonyRef, Instant performedAt,
      int sigKeyVersion, byte[] signature, Instant signedAt, UUID recordedBy, Instant recordedAt) {
  }
}
