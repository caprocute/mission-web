package vn.viettel.okrkpi.task.infrastructure.web;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Phía client giả lập cho kiểm thử E2EE (ADR-27): khoá thiết bị ECDH P-256, khoá nội dung
 * AES-256-GCM có AAD, bọc khoá ECDH-ES rồi AES Key Wrap. KDF ở đây là SHA-256 của bí mật chung, đủ
 * cho kiểm thử vòng mã hoá và giải mã; thư viện {@code @vt/e2ee} dùng Concat KDF theo JWA.
 */
final class E2eeTestClient {

  private static final SecureRandom RANDOM = new SecureRandom();

  private E2eeTestClient() {}

  /** Khoá thiết bị; {@code deviceId} là SHA-256 của SPKI dạng hex. */
  record Device(KeyPair keys, String deviceId) {
    static Device generate() {
      KeyPair kp = ec();
      return new Device(kp, HexFormat.of().formatHex(sha256(kp.getPublic().getEncoded())));
    }
  }

  record Encrypted(byte[] cek, byte[] nonce, byte[] ciphertext, byte[] aadHash) {}

  record Wrapped(byte[] ephemeralSpki, byte[] wrappedKey) {}

  static Encrypted encrypt(byte[] plaintext, byte[] aad) {
    try {
      KeyGenerator g = KeyGenerator.getInstance("AES");
      g.init(256);
      byte[] cek = g.generateKey().getEncoded();
      byte[] nonce = new byte[12];
      RANDOM.nextBytes(nonce);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
      c.updateAAD(aad);
      return new Encrypted(cek, nonce, c.doFinal(plaintext), sha256(aad));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] decrypt(byte[] cek, byte[] nonce, byte[] ciphertext, byte[] aad)
      throws GeneralSecurityException {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
    c.updateAAD(aad);
    return c.doFinal(ciphertext);
  }

  static Wrapped wrap(byte[] cek, PublicKey recipient) {
    try {
      KeyPair eph = ec();
      Cipher c = Cipher.getInstance("AESWrap");
      c.init(Cipher.WRAP_MODE, kek(eph, recipient));
      return new Wrapped(eph.getPublic().getEncoded(), c.wrap(new SecretKeySpec(cek, "AES")));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] unwrap(Device device, byte[] ephemeralSpki, byte[] wrappedKey)
      throws GeneralSecurityException {
    PublicKey eph =
        KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(ephemeralSpki));
    Cipher c = Cipher.getInstance("AESWrap");
    c.init(Cipher.UNWRAP_MODE, kek(device.keys(), eph));
    return c.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY).getEncoded();
  }

  private static SecretKey kek(KeyPair own, PublicKey other) throws GeneralSecurityException {
    KeyAgreement ka = KeyAgreement.getInstance("ECDH");
    ka.init(own.getPrivate());
    ka.doPhase(other, true);
    return new SecretKeySpec(Arrays.copyOf(sha256(ka.generateSecret()), 32), "AES");
  }

  private static KeyPair ec() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      return g.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
