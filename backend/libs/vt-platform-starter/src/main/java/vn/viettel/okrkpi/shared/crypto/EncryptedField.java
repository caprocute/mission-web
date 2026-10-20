package vn.viettel.okrkpi.shared.crypto;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Kết quả mã hoá một trường C2 dùng cho bảng có cặp cột {@code <cột>_enc bytea} và {@code dek_id
 * uuid REFERENCES keyring.dek(id)} (TL-03 "Mã hoá phong bì cho cột C2").
 *
 * @param dekId mã định danh DEK đã dùng (trùng với 16 byte {@code dek_ref} ở offset 1..17 của
 *     {@code ciphertext})
 * @param ciphertext bản mã phong bì đầy đủ: {@code version(1B) || dek_ref(16B) || nonce(12B) ||
 *     body || tag(16B)}
 */
public record EncryptedField(UUID dekId, byte[] ciphertext) {

  public EncryptedField {
    Objects.requireNonNull(dekId, "dekId");
    Objects.requireNonNull(ciphertext, "ciphertext");
    if (ciphertext.length < FieldEncryptor.MIN_ENVELOPE_LENGTH) {
      throw new IllegalArgumentException(
          "Bản mã ngắn hơn kích thước phong bì tối thiểu ("
              + FieldEncryptor.MIN_ENVELOPE_LENGTH
              + " byte)");
    }
    ciphertext = ciphertext.clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof EncryptedField other)) {
      return false;
    }
    return dekId.equals(other.dekId) && Arrays.equals(ciphertext, other.ciphertext);
  }

  @Override
  public int hashCode() {
    return 31 * dekId.hashCode() + Arrays.hashCode(ciphertext);
  }

  @Override
  public String toString() {
    return "EncryptedField[dekId=" + dekId + ", length=" + ciphertext.length + "]";
  }
}
