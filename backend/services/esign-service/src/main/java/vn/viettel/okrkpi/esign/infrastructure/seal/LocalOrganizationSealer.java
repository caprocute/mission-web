package vn.viettel.okrkpi.esign.infrastructure.seal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.esign.application.seal.OrganizationSealer;

/**
 * Dấu số tổ chức bằng khoá EC P-256 trong tệp PEM do OpenBao Agent đặt (một cặp khoá cho cell).
 * Dùng ở môi trường phát triển và khi khoá tổ chức của cell chưa nằm trên HSM của nhà cung cấp; ký
 * {@code NONEwithECDSA} trên băm SHA-256 đã nhận, tương đương {@code SHA256withECDSA} trên tài
 * liệu.
 */
public class LocalOrganizationSealer implements OrganizationSealer {

  static final String ALGORITHM = "SHA256withECDSA";

  private final PrivateKey key;
  private final byte[] publicKey;

  public LocalOrganizationSealer(KeyPair pair) {
    Objects.requireNonNull(pair, "pair");
    if (!"EC".equals(pair.getPrivate().getAlgorithm())) {
      throw new IllegalStateException("Khoá tổ chức phải là EC P-256");
    }
    this.key = pair.getPrivate();
    this.publicKey = pair.getPublic().getEncoded();
  }

  /** Đọc khoá riêng PKCS#8 PEM và khoá công khai SPKI PEM tương ứng. */
  public static LocalOrganizationSealer fromPem(Path privateKey, Path publicKey) {
    try {
      KeyFactory ec = KeyFactory.getInstance("EC");
      PrivateKey priv = ec.generatePrivate(new PKCS8EncodedKeySpec(pem(privateKey)));
      PublicKey pub = ec.generatePublic(new X509EncodedKeySpec(pem(publicKey)));
      return new LocalOrganizationSealer(new KeyPair(pub, priv));
    } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Không đọc được khoá tổ chức", e);
    }
  }

  @Override
  public Seal seal(UUID tenantId, byte[] sha256) {
    try {
      Signature s = Signature.getInstance("NONEwithECDSA");
      s.initSign(key);
      s.update(sha256);
      return new Seal(s.sign(), ALGORITHM, publicKey.clone());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Không ký được bằng khoá tổ chức", e);
    }
  }

  private static byte[] pem(Path path) throws IOException {
    String text = Files.readString(path, StandardCharsets.US_ASCII);
    return Base64.getDecoder()
        .decode(text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
  }
}
