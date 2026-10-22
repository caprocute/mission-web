package vn.viettel.okrkpi.policy.infrastructure.signing;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.policy.application.port.PolicyCaseSigner;

/**
 * Ký ES256 (P1363) ca kiểm thử vàng bằng khoá nạp từ bí mật (PKCS#8 và X.509, base64). Khi chưa cấp
 * khoá, sinh khoá tạm cho pod và cảnh báo. Đây là bộ chuyển đổi tạm cho tới khi ký qua SVC-10 (suy
 * luận, wiki trang 14).
 */
public class KeyPairPolicyCaseSigner implements PolicyCaseSigner {

  private static final Logger log = LoggerFactory.getLogger(KeyPairPolicyCaseSigner.class);
  private static final String JCA = "SHA256withECDSAinP1363Format";

  private final String keyId;
  private final PrivateKey privateKey;
  private final PublicKey publicKey;

  public KeyPairPolicyCaseSigner(String keyId, KeyPair pair) {
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    this.privateKey = pair.getPrivate();
    this.publicKey = pair.getPublic();
  }

  /** Nạp cặp khoá; thiếu một trong hai thì sinh khoá tạm. */
  public static KeyPairPolicyCaseSigner of(
      String keyId, String privateKeyBase64, String publicKeyBase64) {
    try {
      if (privateKeyBase64 == null
          || privateKeyBase64.isBlank()
          || publicKeyBase64 == null
          || publicKeyBase64.isBlank()) {
        log.warn("Chưa cấp khoá ký ca kiểm thử vàng; dùng khoá tạm của pod.");
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return new KeyPairPolicyCaseSigner(keyId + "-tam", g.generateKeyPair());
      }
      KeyFactory kf = KeyFactory.getInstance("EC");
      PrivateKey priv =
          kf.generatePrivate(
              new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64.strip())));
      PublicKey pub =
          kf.generatePublic(
              new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.strip())));
      return new KeyPairPolicyCaseSigner(keyId, new KeyPair(pub, priv));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Khoá ký ca kiểm thử vàng không hợp lệ", e);
    }
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

  /** Kiểm chữ ký bằng khoá công khai của bộ ký. */
  public boolean verify(byte[] data, byte[] signature) {
    try {
      Signature s = Signature.getInstance(JCA);
      s.initVerify(publicKey);
      s.update(data);
      return s.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }
}
