package vn.viettel.okrkpi.integ.infrastructure.cloudevents;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.ParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Khoá ký JWS của cell (DV-13-02) đọc từ tệp JWK Set do OpenBao Agent kết xuất (ví dụ khoá ở
 * transit hoặc kv của OpenBao, hoặc khoá xuất từ HSM theo quy trình nghi thức khoá). Khoá đầu tiên
 * có phần riêng là khoá đang ký; mọi khoá công khai trong tệp đều được công bố ở JWKS để đối tác
 * kiểm được thông điệp ký bằng khoá cũ trong thời gian xoay khoá. Tệp được đọc lại khi đổi thời
 * điểm sửa (OpenBao Agent ghi đè khi xoay).
 *
 * <p>Chỉ nhận EC P-256 ({@code ES256}) hoặc RSA từ 2048 bit ({@code PS256} mặc định, {@code RS256}
 * khi khoá khai {@code alg}); AsyncAPI ghi RS256/PS256, thẻ task ghi ES256 (wiki trang 14).
 */
public class JwkSigningKeys {

  static final Set<JWSAlgorithm> RSA_ALGORITHMS = Set.of(JWSAlgorithm.PS256, JWSAlgorithm.RS256);
  static final int MIN_RSA_BITS = 2048;

  /** Khoá đang ký và bộ khoá công khai. */
  public record Active(String keyId, JWSAlgorithm algorithm, JWSSigner signer, JWKSet publicSet) {}

  private final Path file;
  private FileTime loadedAt;
  private Active active;

  public JwkSigningKeys(Path file) {
    this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
  }

  /** Khoá hiện hành; đọc lại tệp khi tệp đổi. */
  public synchronized Active active() {
    try {
      FileTime modified = Files.getLastModifiedTime(file);
      if (active == null || !modified.equals(loadedAt)) {
        active = load(JWKSet.load(file.toFile()));
        loadedAt = modified;
      }
      return active;
    } catch (IOException e) {
      throw new UncheckedIOException("Không đọc được tệp khoá ký của cell", e);
    } catch (ParseException e) {
      throw new IllegalStateException("Tệp khoá ký của cell không phải JWK Set hợp lệ", e);
    }
  }

  static Active load(JWKSet set) {
    List<JWK> keys = set.getKeys();
    JWK signing =
        keys.stream()
            .filter(JWK::isPrivate)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Tệp khoá ký không có khoá riêng"));
    if (signing.getKeyID() == null || signing.getKeyID().isBlank()) {
      throw new IllegalStateException("Khoá ký phải có kid");
    }
    for (JWK k : keys) {
      check(k);
    }
    try {
      JWSAlgorithm alg = algorithm(signing);
      JWSSigner signer =
          signing instanceof ECKey ec ? new ECDSASigner(ec) : new RSASSASigner((RSAKey) signing);
      return new Active(signing.getKeyID(), alg, signer, set.toPublicJWKSet());
    } catch (JOSEException e) {
      throw new IllegalStateException("Không dựng được bộ ký từ khoá của cell", e);
    }
  }

  static JWSAlgorithm algorithm(JWK key) {
    if (key instanceof ECKey) {
      return JWSAlgorithm.ES256;
    }
    if (key.getAlgorithm() != null) {
      JWSAlgorithm declared = JWSAlgorithm.parse(key.getAlgorithm().getName());
      if (!RSA_ALGORITHMS.contains(declared)) {
        throw new IllegalStateException("Thuật toán ký RSA không được phép: " + declared);
      }
      return declared;
    }
    return JWSAlgorithm.PS256;
  }

  private static void check(JWK key) {
    if (key instanceof ECKey ec) {
      if (!Curve.P_256.equals(ec.getCurve())) {
        throw new IllegalStateException("Khoá EC phải dùng đường cong P-256");
      }
    } else if (key instanceof RSAKey rsa) {
      if (rsa.size() < MIN_RSA_BITS) {
        throw new IllegalStateException("Khoá RSA phải từ " + MIN_RSA_BITS + " bit");
      }
    } else {
      throw new IllegalStateException("Chỉ nhận khoá EC hoặc RSA");
    }
  }
}
