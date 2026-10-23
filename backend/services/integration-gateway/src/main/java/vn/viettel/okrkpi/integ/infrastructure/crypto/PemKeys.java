package vn.viettel.okrkpi.integ.infrastructure.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Đọc khoá PEM (PKCS#8 cho khoá riêng, SubjectPublicKeyInfo cho khoá công khai) do OpenBao Agent
 * đặt vào tệp. Chỉ nhận EC P-256 cho khoá ký và RSA từ 3072 bit cho khoá nhận gói (SEC-20).
 */
public final class PemKeys {

  /** RSA nhận gói tối thiểu (khoá mới từ năm 2027 dùng từ 3072 bit, SEC-20). */
  public static final int MIN_RSA_BITS = 3072;

  private PemKeys() {}

  public static PrivateKey privateKey(Path file, String algorithm) {
    try {
      return KeyFactory.getInstance(algorithm)
          .generatePrivate(
              new PKCS8EncodedKeySpec(der(Files.readString(file, StandardCharsets.US_ASCII))));
    } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Không đọc được khoá riêng " + algorithm, e);
    }
  }

  /** Khoá công khai từ chuỗi PEM hoặc base64 DER. */
  public static PublicKey publicKey(String pemOrBase64, String algorithm) {
    try {
      PublicKey key =
          KeyFactory.getInstance(algorithm)
              .generatePublic(new X509EncodedKeySpec(der(pemOrBase64)));
      check(key);
      return key;
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Không đọc được khoá công khai " + algorithm, e);
    }
  }

  public static PublicKey publicKey(Path file, String algorithm) {
    try {
      return publicKey(Files.readString(file, StandardCharsets.US_ASCII), algorithm);
    } catch (IOException e) {
      throw new IllegalStateException("Không đọc được tệp khoá công khai", e);
    }
  }

  static void check(PublicKey key) {
    if (key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() < MIN_RSA_BITS) {
      throw new IllegalArgumentException("Khoá RSA nhỏ hơn " + MIN_RSA_BITS + " bit");
    }
    if (key instanceof ECPublicKey ec
        && ec.getParams().getCurve().getField().getFieldSize() < 256) {
      throw new IllegalArgumentException("Khoá EC nhỏ hơn P-256");
    }
  }

  static byte[] der(String text) {
    String body = text.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
    return Base64.getDecoder().decode(body);
  }
}
