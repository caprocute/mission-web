package vn.viettel.okrkpi.task.domain.secret;

import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Khoá nội dung (CEK) đã bọc cho một khoá công khai của người nhận ({@code task.e2ee_key_wrap}).
 * Máy chủ chỉ giữ bản bọc; {@code fingerprint} là SHA-256 của SPKI khoá người nhận ({@code
 * org.person_public_key.fingerprint}), trong hợp đồng là {@code deviceId} dạng hex.
 *
 * @param grantedBy người cấp quyền đọc, {@code null} khi chưa lưu
 * @param createdAt thời điểm lưu, {@code null} khi chưa lưu
 * @param revokedAt thời điểm thu hồi, {@code null} khi còn hiệu lực
 */
public record KeyWrap(
    UUID recipientId,
    byte[] fingerprint,
    String algorithm,
    byte[] ephemeralPublicKey,
    byte[] wrappedKey,
    UUID grantedBy,
    Instant createdAt,
    Instant revokedAt) {

  public KeyWrap {
    Objects.requireNonNull(recipientId, "recipientId");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(algorithm, "algorithm");
    Objects.requireNonNull(wrappedKey, "wrappedKey");
  }

  public boolean active() {
    return revokedAt == null;
  }

  /** Vân tay dạng hex chữ thường, cũng là {@code deviceId} của hợp đồng. */
  public String deviceId() {
    return HexFormat.of().formatHex(fingerprint);
  }

  public KeyWrap grantedBy(UUID actor) {
    return new KeyWrap(
        recipientId,
        fingerprint,
        algorithm,
        ephemeralPublicKey,
        wrappedKey,
        actor,
        createdAt,
        null);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof KeyWrap k
        && recipientId.equals(k.recipientId)
        && Arrays.equals(fingerprint, k.fingerprint)
        && algorithm.equals(k.algorithm)
        && Arrays.equals(ephemeralPublicKey, k.ephemeralPublicKey)
        && Arrays.equals(wrappedKey, k.wrappedKey)
        && Objects.equals(grantedBy, k.grantedBy)
        && Objects.equals(createdAt, k.createdAt)
        && Objects.equals(revokedAt, k.revokedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(recipientId, deviceId(), algorithm, Arrays.hashCode(wrappedKey));
  }

  /** Không in khoá đã bọc ra nhật ký. */
  @Override
  public String toString() {
    return "KeyWrap[recipientId=" + recipientId + ", deviceId=" + deviceId() + "]";
  }
}
