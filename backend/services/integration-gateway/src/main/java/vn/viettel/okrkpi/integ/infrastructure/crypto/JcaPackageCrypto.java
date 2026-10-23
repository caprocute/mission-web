package vn.viettel.okrkpi.integ.infrastructure.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import vn.viettel.okrkpi.integ.application.port.PackageCrypto;

/**
 * Mật mã gói chuyển liên cell bằng JCA của JDK (danh sách SEC-20): bản mã AES-256-GCM với IV 12
 * byte ngẫu nhiên và dữ liệu liên kết là mã gói (bản mã không ghép được sang gói khác), khoá nội
 * dung bọc RSA-OAEP SHA-256 (khoá nhận gói RSA từ 3072 bit), chữ ký ECDSA P-256 SHA-256. Khoá nội
 * dung chỉ tồn tại trong lời gọi và được xoá khỏi mảng sau khi dùng.
 */
public class JcaPackageCrypto implements PackageCrypto {

  static final String AES = "AES/GCM/NoPadding";
  static final String OAEP = "RSA/ECB/OAEPPadding";
  static final String SIGNATURE = "SHA256withECDSA";
  static final int IV_BYTES = 12;
  static final int TAG_BITS = 128;
  public static final OAEPParameterSpec OAEP_SHA256 =
      new OAEPParameterSpec(
          "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

  private final PrivateKey signingKey;
  private final PrivateKey decryptionKey;
  private final SecureRandom random;

  public JcaPackageCrypto(PrivateKey signingKey, PrivateKey decryptionKey) {
    this.signingKey = Objects.requireNonNull(signingKey, "signingKey");
    this.decryptionKey = Objects.requireNonNull(decryptionKey, "decryptionKey");
    this.random = new SecureRandom();
  }

  @Override
  public Sealed seal(UUID packageId, byte[] plain, PublicKey recipient) {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256, random);
      SecretKey cek = generator.generateKey();
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher aes = Cipher.getInstance(AES);
      aes.init(Cipher.ENCRYPT_MODE, cek, new GCMParameterSpec(TAG_BITS, iv));
      aes.updateAAD(aad(packageId));
      byte[] body = aes.doFinal(plain);
      Cipher wrap = Cipher.getInstance(OAEP);
      wrap.init(Cipher.WRAP_MODE, recipient, OAEP_SHA256);
      byte[] wrapped = wrap.wrap(cek);
      return new Sealed(
          ByteBuffer.allocate(iv.length + body.length).put(iv).put(body).array(), wrapped);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Không mã hoá được gói chuyển", e);
    }
  }

  @Override
  public byte[] open(UUID packageId, byte[] ciphertext, byte[] wrappedKey) {
    if (ciphertext == null || ciphertext.length <= IV_BYTES || wrappedKey == null) {
      throw new InvalidPackageException("Bản mã gói không hợp lệ", null);
    }
    byte[] raw = null;
    try {
      Cipher unwrap = Cipher.getInstance(OAEP);
      unwrap.init(Cipher.DECRYPT_MODE, decryptionKey, OAEP_SHA256);
      raw = unwrap.doFinal(wrappedKey);
      if (raw.length != 32) {
        throw new InvalidPackageException("Khoá nội dung không đúng độ dài", null);
      }
      Cipher aes = Cipher.getInstance(AES);
      aes.init(
          Cipher.DECRYPT_MODE,
          new javax.crypto.spec.SecretKeySpec(raw, "AES"),
          new GCMParameterSpec(TAG_BITS, ciphertext, 0, IV_BYTES));
      aes.updateAAD(aad(packageId));
      return aes.doFinal(ciphertext, IV_BYTES, ciphertext.length - IV_BYTES);
    } catch (GeneralSecurityException e) {
      throw new InvalidPackageException("Không giải được gói chuyển", e);
    } finally {
      if (raw != null) {
        Arrays.fill(raw, (byte) 0);
      }
    }
  }

  @Override
  public byte[] sign(byte[] data) {
    try {
      Signature s = Signature.getInstance(SIGNATURE);
      s.initSign(signingKey, random);
      s.update(data);
      return s.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Không ký được gói chuyển", e);
    }
  }

  @Override
  public boolean verify(byte[] data, byte[] signature, PublicKey signer) {
    if (data == null || signature == null || signer == null) {
      return false;
    }
    try {
      Signature s = Signature.getInstance(SIGNATURE);
      s.initVerify(signer);
      s.update(data);
      return s.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  private static byte[] aad(UUID packageId) {
    return ("okrkpi-transfer-v1:" + packageId).getBytes(StandardCharsets.UTF_8);
  }
}
