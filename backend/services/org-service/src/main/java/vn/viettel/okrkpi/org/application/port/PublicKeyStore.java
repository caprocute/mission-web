package vn.viettel.okrkpi.org.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Adapter {@code org.person_public_key} (danh bạ khoá công khai C3) trong tenant của giao dịch. */
public interface PublicKeyStore {

  boolean fingerprintExists(byte[] fingerprint);

  /** Số khoá còn hiệu lực (chưa thu hồi, chưa hết hạn) của một người. */
  int countActive(UUID personId, Instant now);

  void insert(StoredKey key, UUID actor);

  Optional<StoredKey> find(UUID id);

  Optional<StoredKey> byFingerprint(byte[] fingerprint);

  /** Khoá của một người, mới nhất trước. */
  List<StoredKey> byPerson(UUID personId);

  /** Thu hồi khi {@code row_version} khớp; trả số dòng đã sửa. */
  int revoke(UUID id, int expectedVersion, UUID actor, String reason, Instant at);

  record StoredKey(UUID id, UUID personId, String keyAlg, byte[] spki, byte[] fingerprint, String source,
      String status, Instant validFrom, Instant validTo, int sigKeyVersion, byte[] signature, Instant signedAt,
      int rowVersion) {
  }
}
