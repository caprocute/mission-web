package vn.viettel.okrkpi.shared.crypto;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Triển khai {@link CryptoProvider} mặc định dùng JCA chuẩn: {@code AES/GCM/NoPadding} (khoá 256
 * bit, nonce 96 bit, tag 128 bit) và {@code HmacSHA256}.
 */
public final class JcaAesGcmCryptoProvider implements CryptoProvider {

  private static final String AES_ALGORITHM = "AES";
  private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";
  private static final int TAG_LENGTH_BITS = TAG_LENGTH_BYTES * 8;

  private static final ThreadLocal<Cipher> CIPHER_CACHE =
      ThreadLocal.withInitial(
          () -> {
            try {
              return Cipher.getInstance(AES_GCM_TRANSFORMATION);
            } catch (GeneralSecurityException e) {
              throw new CryptoException("Không khởi tạo được Cipher AES/GCM/NoPadding", e);
            }
          });

  private static final ThreadLocal<Mac> MAC_CACHE =
      ThreadLocal.withInitial(
          () -> {
            try {
              return Mac.getInstance(HMAC_SHA256_ALGORITHM);
            } catch (NoSuchAlgorithmException e) {
              throw new CryptoException("Không khởi tạo được Mac HmacSHA256", e);
            }
          });

  private final SecureRandom secureRandom;

  public JcaAesGcmCryptoProvider() {
    this(new SecureRandom());
  }

  public JcaAesGcmCryptoProvider(SecureRandom secureRandom) {
    this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
  }

  @Override
  public byte[] generateKey() {
    byte[] key = new byte[KEY_LENGTH_BYTES];
    secureRandom.nextBytes(key);
    return key;
  }

  @Override
  public byte[] generateNonce() {
    byte[] nonce = new byte[NONCE_LENGTH_BYTES];
    secureRandom.nextBytes(nonce);
    return nonce;
  }

  @Override
  public byte[] encrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) {
    validateKeyAndNonce(key, nonce);
    Objects.requireNonNull(aad, "aad");
    Objects.requireNonNull(plaintext, "plaintext");
    if (aad.length == 0) {
      throw new CryptoException("AAD không được rỗng");
    }
    try {
      Cipher cipher = CIPHER_CACHE.get();
      SecretKeySpec keySpec = new SecretKeySpec(key, AES_ALGORITHM);
      GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, nonce);
      cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
      cipher.updateAAD(aad);
      return cipher.doFinal(plaintext);
    } catch (GeneralSecurityException e) {
      throw new CryptoException("Lỗi mã hoá AES-256-GCM", e);
    }
  }

  @Override
  public byte[] decrypt(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertextWithTag) {
    validateKeyAndNonce(key, nonce);
    Objects.requireNonNull(aad, "aad");
    Objects.requireNonNull(ciphertextWithTag, "ciphertextWithTag");
    if (aad.length == 0) {
      throw new CryptoException("AAD không được rỗng");
    }
    if (ciphertextWithTag.length < TAG_LENGTH_BYTES) {
      throw new CryptoException("Bản mã ngắn hơn độ dài thẻ xác thực GCM (16 byte)");
    }
    try {
      Cipher cipher = CIPHER_CACHE.get();
      SecretKeySpec keySpec = new SecretKeySpec(key, AES_ALGORITHM);
      GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, nonce);
      cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
      cipher.updateAAD(aad);
      return cipher.doFinal(ciphertextWithTag);
    } catch (GeneralSecurityException e) {
      throw new CryptoException("Xác thực thẻ GCM hoặc AAD thất bại khi giải mã", e);
    }
  }

  @Override
  public byte[] hmacSha256(byte[] key, byte[] data) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(data, "data");
    if (key.length < 16) {
      throw new CryptoException("Khoá HMAC phải có độ dài tối thiểu 16 byte");
    }
    try {
      Mac mac = MAC_CACHE.get();
      mac.init(new SecretKeySpec(key, HMAC_SHA256_ALGORITHM));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new CryptoException("Lỗi tính HMAC-SHA256", e);
    }
  }

  private static void validateKeyAndNonce(byte[] key, byte[] nonce) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(nonce, "nonce");
    if (key.length != KEY_LENGTH_BYTES) {
      throw new CryptoException(
          "Độ dài khoá AES-256 phải đúng " + KEY_LENGTH_BYTES + " byte, nhận: " + key.length);
    }
    if (nonce.length != NONCE_LENGTH_BYTES) {
      throw new CryptoException(
          "Độ dài nonce GCM phải đúng " + NONCE_LENGTH_BYTES + " byte, nhận: " + nonce.length);
    }
  }
}
