package vn.viettel.okrkpi.shared.crypto;

/**
 * Giao diện mật mã đối xứng AEAD và HMAC cho mã hoá trường phong bì C2 (ADR-06, ADR-30).
 *
 * <p>Mặc định dùng {@link JcaAesGcmCryptoProvider} (AES-256-GCM chuẩn JCA); ở bản triển khai cách
 * ly (air-gapped) có thể thay bằng nhà cung cấp mật mã của Ban Cơ yếu Chính phủ mà không đổi định
 * dạng phong bì hay mã nghiệp vụ.
 */
public interface CryptoProvider {

  /** Phiên bản thuật ngữ/định dạng bản mã hiện hành ({@code 0x01}). */
  byte VERSION_1 = 0x01;

  /** Độ dài khoá DEK chuẩn: 32 byte (256 bit). */
  int KEY_LENGTH_BYTES = 32;

  /** Độ dài nonce chuẩn cho GCM: 12 byte (96 bit). */
  int NONCE_LENGTH_BYTES = 12;

  /** Độ dài thẻ xác thực (authentication tag): 16 byte (128 bit). */
  int TAG_LENGTH_BYTES = 16;

  /** Phiên bản thuật toán ghi ở byte đầu tiên của bản mã. */
  default byte algorithmVersion() {
    return VERSION_1;
  }

  /** Sinh khoá đối xứng ngẫu nhiên 32 byte (256 bit). */
  byte[] generateKey();

  /** Sinh nonce ngẫu nhiên 12 byte (96 bit) cho một lần mã hoá. */
  byte[] generateNonce();

  /**
   * Mã hoá AEAD: trả về {@code ciphertext || tag (16 byte)}.
   *
   * @param key khoá 32 byte
   * @param nonce nonce 12 byte
   * @param aad dữ liệu xác thực bổ sung (AAD), không được {@code null}
   * @param plaintext bản rõ, không được {@code null}
   * @return mảng byte gồm bản mã nối liền thẻ xác thực 16 byte
   */
  byte[] encrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext);

  /**
   * Giải mã AEAD và kiểm tra thẻ xác thực cùng AAD.
   *
   * @param key khoá 32 byte
   * @param nonce nonce 12 byte
   * @param aad dữ liệu xác thực bổ sung (AAD), phải khớp lúc mã hoá
   * @param ciphertextWithTag mảng byte gồm {@code ciphertext || tag (16 byte)}
   * @return bản rõ
   * @throws CryptoException nếu thẻ xác thực hoặc AAD không khớp, hoặc đầu vào không hợp lệ
   */
  byte[] decrypt(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertextWithTag);

  /**
   * Tính HMAC-SHA256 (32 byte) phục vụ dẫn xuất khoá và chỉ mục mù.
   *
   * @param key khoá HMAC (tối thiểu 16 byte)
   * @param data dữ liệu cần băm
   * @return mảng 32 byte kết quả HMAC-SHA256
   */
  byte[] hmacSha256(byte[] key, byte[] data);
}
