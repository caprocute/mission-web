package vn.viettel.okrkpi.org.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Token giả lập Keycloak ký bằng khoá RSA sinh lúc chạy kiểm thử (không có khoá cố định trong repo). */
public final class TestJwt {

  private static final KeyPair KEYS;

  static {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      KEYS = g.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private TestJwt() {
  }

  public static RSAPublicKey publicKey() {
    return (RSAPublicKey) KEYS.getPublic();
  }

  public static String token(UUID tenant, UUID subject, List<String> roles, String acr, UUID unitId) {
    try {
      JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
          .subject(subject.toString())
          .issuer("https://kiem-thu.invalid/realms/mission")
          .issueTime(new Date())
          .expirationTime(Date.from(Instant.now().plusSeconds(600)))
          .claim("tenant", tenant.toString())
          .claim("realm_access", Map.of("roles", roles))
          .claim("acr", acr)
          .claim("auth_time", Instant.now().getEpochSecond());
      if (unitId != null) {
        claims.claim("unit_id", unitId.toString());
      }
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
      jwt.sign(new RSASSASigner(KEYS.getPrivate()));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }
}
