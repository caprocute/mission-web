package vn.viettel.okrkpi.integ.infrastructure.cloudevents;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import java.util.Objects;
import vn.viettel.okrkpi.integ.application.port.PayloadSigner;

/**
 * Ký JWS bằng Nimbus JOSE (đã có qua Spring Security) với khoá của cell (DV-13-02). Tiêu đề JWS
 * mang {@code alg}, {@code kid} và {@code cty=cloudevents+json}; tải trọng mã hoá base64url chuẩn
 * (không dùng {@code b64=false} của RFC 7797, để thư viện phía đối tác kiểm được bằng cách thông
 * thường).
 */
public class NimbusPayloadSigner implements PayloadSigner {

  static final String CONTENT_TYPE = "cloudevents+json";

  private final JwkSigningKeys keys;

  public NimbusPayloadSigner(JwkSigningKeys keys) {
    this.keys = Objects.requireNonNull(keys, "keys");
  }

  @Override
  public String signDetached(byte[] payload) {
    return sign(payload).serialize(true);
  }

  @Override
  public String signCompact(byte[] payload) {
    return sign(payload).serialize();
  }

  @Override
  public String publicJwks() {
    return keys.active().publicSet().toString(true);
  }

  private JWSObject sign(byte[] payload) {
    JwkSigningKeys.Active key = keys.active();
    JWSObject jws =
        new JWSObject(
            new JWSHeader.Builder(key.algorithm())
                .keyID(key.keyId())
                .contentType(CONTENT_TYPE)
                .build(),
            new Payload(Objects.requireNonNull(payload, "payload")));
    try {
      jws.sign(key.signer());
    } catch (JOSEException e) {
      throw new IllegalStateException("Không ký được thông điệp ra", e);
    }
    return jws;
  }
}
