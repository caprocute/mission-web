package vn.viettel.okrkpi.iam.events;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * DV-02-08 · Sinh, chuẩn hoá và băm mã khôi phục MFA. Mã gồm 16 ký tự trong bảng 32 chữ và số dễ đọc (bỏ 0, 1, I, O),
 * chia bốn nhóm, tức 80 bit ngẫu nhiên (suy luận, thẻ task nêu 16 ký tự). Chỉ lưu băm PBKDF2-HMAC-SHA256 có muối; so
 * khớp thời gian hằng.
 */
final class RecoveryCodes {

  static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
  static final int LENGTH = 16;
  static final int ITERATIONS = 210_000;
  private static final SecureRandom RANDOM = new SecureRandom();

  record Hashed(String salt, String hash, int iterations) {
  }

  private RecoveryCodes() {
  }

  static String generate() {
    StringBuilder b = new StringBuilder(LENGTH + 3);
    for (int i = 0; i < LENGTH; i++) {
      if (i > 0 && i % 4 == 0) {
        b.append('-');
      }
      b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return b.toString();
  }

  /** Bỏ khoảng trắng, gạch nối; chữ thường thành chữ hoa. Trả {@code null} nếu không đúng dạng. */
  static String normalize(String input) {
    if (input == null) {
      return null;
    }
    String s = input.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    if (s.length() != LENGTH) {
      return null;
    }
    for (char c : s.toCharArray()) {
      if (ALPHABET.indexOf(c) < 0) {
        return null;
      }
    }
    return s;
  }

  static Hashed hash(String code) {
    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    return new Hashed(Base64.getEncoder().encodeToString(salt), derive(normalize(code), salt, ITERATIONS), ITERATIONS);
  }

  static boolean matches(String input, Hashed stored) {
    String n = normalize(input);
    if (n == null || stored == null) {
      return false;
    }
    String candidate = derive(n, Base64.getDecoder().decode(stored.salt()), stored.iterations());
    return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.US_ASCII),
        stored.hash().getBytes(StandardCharsets.US_ASCII));
  }

  /** Băm SHA-256 của khoá Idempotency-Key để so lần gọi lặp, không lưu khoá dạng rõ. */
  static String keyDigest(String key) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String derive(String normalized, byte[] salt, int iterations) {
    try {
      PBEKeySpec spec = new PBEKeySpec(normalized.toCharArray(), salt, iterations, 256);
      byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
      spec.clearPassword();
      return Base64.getEncoder().encodeToString(h);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
