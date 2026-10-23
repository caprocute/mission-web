package vn.viettel.okrkpi.integ.infrastructure.cloudevents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bộ kiểm chứng phía nhận cho thông điệp CloudEvents do SVC-13 gửi (DV-13-02): dùng trong kiểm thử
 * và cấp cho đối tác cùng tài liệu AsyncAPI. Chỉ phụ thuộc Nimbus JOSE, Jackson và
 * json-schema-validator.
 *
 * <ol>
 *   <li>Kiểm chữ ký JWS (tách hoặc gọn) bằng JWKS công bố của cell, chỉ nhận ES256, PS256, RS256 và
 *       khoá có {@code kid} trùng tiêu đề.
 *   <li>Kiểm phong bì theo {@code cloudevents/phong-bi-ra-ngoai.schema.json}.
 *   <li>Kiểm {@code data} theo lược đồ JSON của từng {@code type} nếu bên nhận cung cấp.
 * </ol>
 */
public class CloudEventVerifier {

  public static final String ENVELOPE_SCHEMA = "/cloudevents/phong-bi-ra-ngoai.schema.json";
  static final Set<JWSAlgorithm> ALGORITHMS =
      Set.of(JWSAlgorithm.ES256, JWSAlgorithm.PS256, JWSAlgorithm.RS256);

  /** Thông điệp không qua kiểm chứng; {@code problems} chỉ mô tả lỗi, không chứa dữ liệu. */
  public static class VerificationException extends Exception {
    private static final long serialVersionUID = 1L;
    private final transient List<String> problems;

    public VerificationException(String message, List<String> problems) {
      super(message);
      this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
      return problems;
    }
  }

  private static final JsonSchemaFactory SCHEMAS =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final SchemaValidatorsConfig CONFIG =
      SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();

  private final JWKSet keys;
  private final ObjectMapper json;
  private final JsonSchema envelope;
  private final Map<String, JsonSchema> dataSchemas;

  /**
   * @param jwks JWKS công bố của cell gửi
   * @param dataSchemas lược đồ JSON của {@code data} theo {@code type}; có thể rỗng
   */
  public CloudEventVerifier(String jwks, Map<String, String> dataSchemas) {
    try {
      this.keys = JWKSet.parse(Objects.requireNonNull(jwks, "jwks"));
    } catch (ParseException e) {
      throw new IllegalArgumentException("JWKS không hợp lệ", e);
    }
    this.json = new ObjectMapper();
    try (InputStream in = CloudEventVerifier.class.getResourceAsStream(ENVELOPE_SCHEMA)) {
      if (in == null) {
        throw new IllegalStateException("Thiếu lược đồ phong bì " + ENVELOPE_SCHEMA);
      }
      this.envelope = SCHEMAS.getSchema(in, CONFIG);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    this.dataSchemas =
        dataSchemas.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, e -> SCHEMAS.getSchema(e.getValue(), CONFIG)));
  }

  /** Thân là phong bì JSON, chữ ký tách ở tiêu đề {@code X-JWS-Signature}. */
  public JsonNode verifyDetached(byte[] body, String detachedSignature)
      throws VerificationException {
    JWSObject jws;
    try {
      jws =
          JWSObject.parse(
              Objects.requireNonNull(detachedSignature, "detachedSignature"),
              new Payload(Objects.requireNonNull(body, "body")));
    } catch (ParseException e) {
      throw new VerificationException("Chữ ký không đúng định dạng JWS", List.of("jws"));
    }
    verifySignature(jws);
    return validate(body);
  }

  /** Thân là chuỗi JWS dạng gọn. */
  public JsonNode verifyCompact(String compact) throws VerificationException {
    JWSObject jws;
    try {
      jws = JWSObject.parse(Objects.requireNonNull(compact, "compact"));
    } catch (ParseException e) {
      throw new VerificationException("Thân không đúng định dạng JWS", List.of("jws"));
    }
    verifySignature(jws);
    return validate(jws.getPayload().toBytes());
  }

  private void verifySignature(JWSObject jws) throws VerificationException {
    JWSAlgorithm alg = jws.getHeader().getAlgorithm();
    if (!ALGORITHMS.contains(alg)) {
      throw new VerificationException("Thuật toán ký không được chấp nhận", List.of("alg"));
    }
    String kid = jws.getHeader().getKeyID();
    JWK key = kid == null ? null : keys.getKeyByKeyId(kid);
    if (key == null) {
      throw new VerificationException("Không có khoá công khai cho kid của chữ ký", List.of("kid"));
    }
    try {
      JWSVerifier verifier =
          new DefaultJWSVerifierFactory().createJWSVerifier(jws.getHeader(), publicKey(key));
      if (!jws.verify(verifier)) {
        throw new VerificationException("Chữ ký không khớp nội dung", List.of("signature"));
      }
    } catch (JOSEException e) {
      throw new VerificationException("Không kiểm được chữ ký", List.of("signature"));
    }
  }

  private static java.security.Key publicKey(JWK key) throws JOSEException {
    if (key instanceof com.nimbusds.jose.jwk.ECKey ec) {
      return ec.toPublicKey();
    }
    if (key instanceof com.nimbusds.jose.jwk.RSAKey rsa) {
      return rsa.toPublicKey();
    }
    throw new JOSEException("Loại khoá không được hỗ trợ");
  }

  private JsonNode validate(byte[] body) throws VerificationException {
    JsonNode ce;
    try {
      ce = json.readTree(new String(body, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new VerificationException("Phong bì không phải JSON", List.of("json"));
    }
    if (ce == null) {
      throw new VerificationException("Phong bì rỗng", List.of("json"));
    }
    List<String> problems = problems(envelope.validate(ce));
    JsonSchema data = dataSchemas.get(ce.path("type").asText());
    if (problems.isEmpty() && data != null) {
      problems = problems(data.validate(ce.path("data")));
    }
    if (!problems.isEmpty()) {
      throw new VerificationException("Phong bì không khớp lược đồ", problems);
    }
    return ce;
  }

  private static List<String> problems(Set<ValidationMessage> messages) {
    return messages.stream()
        .map(m -> m.getInstanceLocation() + " " + m.getType())
        .sorted()
        .toList();
  }
}
