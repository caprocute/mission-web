package vn.viettel.okrkpi.org.support;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import vn.viettel.okrkpi.org.application.port.FieldEncryptor;

/**
 * Hiện thực chỉ dùng trong kiểm thử của {@link FieldEncryptor}: AES-256-GCM với DEK sinh ngẫu nhiên lúc chạy, AAD gắn
 * {@code tenant|bảng.cột|row_id} (TL-03 "Mã hoá cột C2"), chỉ mục mù HMAC-SHA256. Không có khoá cố định trong repo.
 */
public final class AesGcmTestFieldEncryptor implements FieldEncryptor {

  private static final SecureRandom RANDOM = new SecureRandom();
  private final UUID dekId = UUID.randomUUID();
  private final SecretKey dek;
  private final SecretKey hmacKey;

  public AesGcmTestFieldEncryptor() {
    try {
      KeyGenerator g = KeyGenerator.getInstance("AES");
      g.init(256);
      dek = g.generateKey();
      byte[] h = new byte[32];
      RANDOM.nextBytes(h);
      hmacKey = new SecretKeySpec(h, "HmacSHA256");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public UUID dekId() {
    return dekId;
  }

  private static byte[] aad(UUID tenantId, String column, UUID rowId) {
    return (tenantId + "|" + column + "|" + rowId).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public Encrypted encrypt(UUID tenantId, String column, UUID rowId, byte[] plaintext) {
    try {
      byte[] iv = new byte[12];
      RANDOM.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(128, iv));
      c.updateAAD(aad(tenantId, column, rowId));
      byte[] ct = c.doFinal(plaintext);
      return new Encrypted(dekId, ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public byte[] decrypt(UUID tenantId, String column, UUID rowId, UUID dekId, byte[] ciphertext) {
    if (!this.dekId.equals(dekId)) {
      throw new IllegalArgumentException("DEK không khớp");
    }
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(128, ciphertext, 0, 12));
      c.updateAAD(aad(tenantId, column, rowId));
      return c.doFinal(ciphertext, 12, ciphertext.length - 12);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Giải mã thất bại", e);
    }
  }

  @Override
  public byte[] blindIndex(UUID tenantId, String column, String normalizedValue) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(hmacKey);
      return mac.doFinal((tenantId + "|" + column + "|" + normalizedValue).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
