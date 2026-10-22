package vn.viettel.okrkpi.analytics.infrastructure;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import vn.viettel.okrkpi.analytics.domain.Scope;

/**
 * Token ngắn hạn cho Cube (DV-15-03 {@code checkAuth}): HS256 ký bằng {@code CUBEJS_API_SECRET},
 * mang {@code tenant} và {@code org_path} của phạm vi đã dựng từ token Keycloak của người xem.
 */
final class CubeTokens {

  static final Duration LIFETIME = Duration.ofMinutes(1);

  private final MACSigner signer;
  private final Clock clock;

  CubeTokens(String secret, Clock clock) {
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException("CUBEJS_API_SECRET phải dài ít nhất 32 byte");
    }
    try {
      this.signer = new MACSigner(secret.getBytes(StandardCharsets.UTF_8));
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
    this.clock = clock;
  }

  String issue(Scope scope) {
    Instant now = clock.instant();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject("analytics-api")
            .claim("tenant", scope.tenant().toString())
            .claim("org_path", scope.orgPath())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(LIFETIME)))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    try {
      jwt.sign(signer);
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
    return jwt.serialize();
  }
}
