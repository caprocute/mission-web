package vn.viettel.okrkpi.org.application.port;

import java.util.UUID;

/**
 * Mã hoá trường C2 (AES-256-GCM, mã hoá phong bì với DEK trong {@code keyring.dek}) và chỉ mục mù HMAC. Hiện thực
 * đích là {@code FieldEncryptor}/{@code BlindIndexer} của thư viện nền NEN-015; trước khi có, service không ghi được
 * cột C2 (hiện thực mặc định từ chối), không bao giờ lưu dạng rõ.
 */
public interface FieldEncryptor {

  Encrypted encrypt(UUID tenantId, String column, UUID rowId, byte[] plaintext);

  byte[] decrypt(UUID tenantId, String column, UUID rowId, UUID dekId, byte[] ciphertext);

  byte[] blindIndex(UUID tenantId, String column, String normalizedValue);

  record Encrypted(UUID dekId, byte[] ciphertext) {
  }
}
