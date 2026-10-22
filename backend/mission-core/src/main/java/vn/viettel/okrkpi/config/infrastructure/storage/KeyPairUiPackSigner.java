package vn.viettel.okrkpi.config.infrastructure.storage;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.config.application.port.UiPackSigner;

/**
 * Ký ES256 bằng khoá nền tảng nạp từ bí mật (PKCS#8, base64), theo TL-01 "chữ ký số của nền tảng".
 * Khi chưa cấp khoá, sinh khoá tạm cho pod và cảnh báo; chuyển sang ký qua SVC-10 (HSM) để sau (suy
 * luận, wiki trang 14).
 */
public class KeyPairUiPackSigner implements UiPackSigner {

  private static final Logger log = LoggerFactory.getLogger(KeyPairUiPackSigner.class);
  private static final String JCA = "SHA256withECDSAinP1363Format";

  private final String keyId;
  private final PrivateKey privateKey;
  private final PublicKey publicKey;

  public KeyPairUiPackSigner(String keyId, KeyPair pair) {
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    this.privateKey = pair.getPrivate();
    this.publicKey = pair.getPublic();
  }

  /**
   * Nạp cặp khoá: bí mật PKCS#8 và công khai X.509 (DER, base64); thiếu một trong hai thì sinh khoá
   * tạm.
   */
  public static KeyPairUiPackSigner of(
      String keyId, String privateKeyBase64, String publicKeyBase64) {
    try {
      if (privateKeyBase64 == null
          || privateKeyBase64.isBlank()
          || publicKeyBase64 == null
          || publicKeyBase64.isBlank()) {
        log.warn(
            "Chưa cấp khoá ký UI Pack; dùng khoá tạm của pod, shell sẽ không kiểm được chữ ký.");
        return new KeyPairUiPackSigner(keyId + "-tam", generate());
      }
      KeyFactory kf = KeyFactory.getInstance("EC");
      ECPrivateKey priv =
          (ECPrivateKey)
              kf.generatePrivate(
                  new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64.strip())));
      PublicKey pub =
          kf.generatePublic(
              new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.strip())));
      KeyPairUiPackSigner signer = new KeyPairUiPackSigner(keyId, new KeyPair(pub, priv));
      byte[] probe = "vt-ui-pack".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      if (!verify(signer.publicKey(), probe, signer.sign(probe))) {
        throw new IllegalStateException("Khoá công khai không khớp khoá bí mật ký UI Pack");
      }
      return signer;
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Khoá ký UI Pack không hợp lệ", e);
    }
  }

  static KeyPair generate() throws GeneralSecurityException {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    return g.generateKeyPair();
  }

  @Override
  public String keyId() {
    return keyId;
  }

  @Override
  public byte[] sign(byte[] data) {
    try {
      Signature s = Signature.getInstance(JCA);
      s.initSign(privateKey);
      s.update(data);
      return s.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public byte[] publicKey() {
    return publicKey.getEncoded();
  }

  /** Kiểm chữ ký P1363 với khoá công khai DER. */
  public static boolean verify(byte[] publicKeyDer, byte[] data, byte[] signature) {
    try {
      PublicKey key =
          KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKeyDer));
      Signature s = Signature.getInstance(JCA);
      s.initVerify(key);
      s.update(data);
      return s.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }
}
