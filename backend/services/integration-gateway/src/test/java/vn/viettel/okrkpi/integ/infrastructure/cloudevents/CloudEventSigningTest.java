package vn.viettel.okrkpi.integ.infrastructure.cloudevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.viettel.okrkpi.integ.application.outbound.CloudEventsMapper;
import vn.viettel.okrkpi.integ.application.outbound.CloudEventsMapperTest;
import vn.viettel.okrkpi.integ.application.outbound.OutboundPolicy;
import vn.viettel.okrkpi.integ.infrastructure.cloudevents.CloudEventVerifier.VerificationException;
import vn.viettel.okrkpi.shared.kernel.Classification;

/**
 * Ký JWS bằng khoá của cell và kiểm ở phía nhận (DV-13-02): chữ ký kiểm được bằng JWKS công bố, sửa
 * một ký tự tải trọng thì thất bại, phong bì sai lược đồ bị từ chối, xoay khoá vẫn kiểm được thông
 * điệp ký bằng khoá cũ.
 */
class CloudEventSigningTest {

  static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path dir;

  static byte[] evt12() throws Exception {
    CloudEventsMapper mapper =
        new CloudEventsMapper(
            "cell-hcm",
            "https://api.okr.viettel.vn/schemas",
            JSON,
            Clock.fixed(Instant.parse("2027-01-15T09:30:12.512Z"), ZoneOffset.UTC));
    return mapper.toJson(
        mapper.fromHeaders(
            CloudEventsMapperTest.TOPIC,
            CloudEventsMapperTest.headers(),
            "appraisal-profile/0192a3e0-1111-7aaa-8bbb-222233334444",
            CloudEventsMapperTest.evt12Data(),
            Classification.C1),
        OutboundPolicy.defaults("CSDL_CBCCVC"));
  }

  Path writeKeys(JWK... keys) throws Exception {
    Path file = dir.resolve("jwks.json");
    Files.writeString(file, new JWKSet(List.of(keys)).toString(false));
    return file;
  }

  static ECKey ec(String kid) throws Exception {
    return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
  }

  @Test
  void es256DetachedSignatureVerifiesAndTamperingFails() throws Exception {
    NimbusPayloadSigner signer =
        new NimbusPayloadSigner(new JwkSigningKeys(writeKeys(ec("cell-hcm-2027-01"))));
    byte[] body = evt12();
    String detached = signer.signDetached(body);
    assertThat(detached).matches("^[A-Za-z0-9_-]+\\.\\.[A-Za-z0-9_-]+$");
    assertThat(signer.publicJwks()).doesNotContain("\"d\"");

    CloudEventVerifier verifier = new CloudEventVerifier(signer.publicJwks(), Map.of());
    JsonNode ce = verifier.verifyDetached(body, detached);
    assertThat(ce.path("data").path("subjectRef").path("scheme").asText()).isEqualTo("cbccvc");

    String text = new String(body, StandardCharsets.UTF_8);
    byte[] tampered = text.replace("84.25", "94.25").getBytes(StandardCharsets.UTF_8);
    assertThat(tampered).isNotEqualTo(body);
    assertThatThrownBy(() -> verifier.verifyDetached(tampered, detached))
        .isInstanceOf(VerificationException.class)
        .hasMessageContaining("Chữ ký không khớp");
    assertThatThrownBy(() -> verifier.verifyDetached(body, "không-phải-jws"))
        .isInstanceOf(VerificationException.class);
  }

  @Test
  void compactAndRsaKeysAreSupported() throws Exception {
    RSAKey rsa = new RSAKeyGenerator(2048).keyID("rsa-ps").generate();
    NimbusPayloadSigner ps = new NimbusPayloadSigner(new JwkSigningKeys(writeKeys(rsa)));
    byte[] body = evt12();
    String compact = ps.signCompact(body);
    assertThat(com.nimbusds.jose.JWSObject.parse(compact).getHeader().getAlgorithm())
        .isEqualTo(JWSAlgorithm.PS256);
    assertThat(com.nimbusds.jose.JWSObject.parse(compact).getHeader().getContentType())
        .isEqualTo("cloudevents+json");
    CloudEventVerifier verifier = new CloudEventVerifier(ps.publicJwks(), Map.of());
    assertThat(verifier.verifyCompact(compact).path("id").asText())
        .isEqualTo(CloudEventsMapperTest.EVENT.toString());
    String[] parts = compact.split("\\.");
    char c = parts[1].charAt(10);
    parts[1] = parts[1].substring(0, 10) + (c == 'A' ? 'B' : 'A') + parts[1].substring(11);
    assertThatThrownBy(() -> verifier.verifyCompact(String.join(".", parts)))
        .isInstanceOf(VerificationException.class);
    assertThatThrownBy(() -> verifier.verifyCompact("abc"))
        .isInstanceOf(VerificationException.class);

    RSAKey rs = new RSAKeyGenerator(2048).keyID("rsa-rs").algorithm(JWSAlgorithm.RS256).generate();
    assertThat(JwkSigningKeys.algorithm(rs)).isEqualTo(JWSAlgorithm.RS256);
    RSAKey bad = new RSAKeyGenerator(2048).keyID("rsa-hs").algorithm(JWSAlgorithm.HS256).generate();
    assertThatThrownBy(() -> JwkSigningKeys.algorithm(bad))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rotationKeepsOldKeyPublishedAndUnknownKidIsRefused() throws Exception {
    ECKey old = ec("cu");
    Path file = writeKeys(old);
    JwkSigningKeys keys = new JwkSigningKeys(file);
    NimbusPayloadSigner signer = new NimbusPayloadSigner(keys);
    byte[] body = evt12();
    String signedOld = signer.signDetached(body);

    ECKey fresh = ec("moi");
    Files.writeString(file, new JWKSet(List.of(fresh, old.toPublicJWK())).toString(false));
    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
    assertThat(keys.active().keyId()).isEqualTo("moi");
    CloudEventVerifier verifier = new CloudEventVerifier(signer.publicJwks(), Map.of());
    verifier.verifyDetached(body, signedOld);
    verifier.verifyDetached(body, signer.signDetached(body));

    CloudEventVerifier stranger =
        new CloudEventVerifier(new JWKSet(ec("khac").toPublicJWK()).toString(), Map.of());
    assertThatThrownBy(() -> stranger.verifyDetached(body, signedOld))
        .isInstanceOf(VerificationException.class)
        .hasMessageContaining("kid");
  }

  @Test
  void keyFileIsValidatedStrictly() throws Exception {
    assertThatThrownBy(() -> new JwkSigningKeys(writeKeys(ec("x").toPublicJWK())).active())
        .hasMessageContaining("khoá riêng");
    assertThatThrownBy(
            () ->
                new JwkSigningKeys(writeKeys(new ECKeyGenerator(Curve.P_256).generate())).active())
        .hasMessageContaining("kid");
    assertThatThrownBy(
            () ->
                new JwkSigningKeys(
                        writeKeys(new ECKeyGenerator(Curve.P_384).keyID("p384").generate()))
                    .active())
        .hasMessageContaining("P-256");
    assertThatThrownBy(
            () ->
                new JwkSigningKeys(
                        writeKeys(new RSAKeyGenerator(1024, true).keyID("r1024").generate()))
                    .active())
        .hasMessageContaining("2048");
    Path broken = dir.resolve("broken.json");
    Files.writeString(broken, "{");
    assertThatThrownBy(() -> new JwkSigningKeys(broken).active()).hasMessageContaining("JWK Set");
    assertThatThrownBy(() -> new JwkSigningKeys(dir.resolve("khong-co.json")).active())
        .hasMessageContaining("Không đọc được");
  }

  @Test
  void envelopeAndDataSchemasAreEnforcedAtReceiver() throws Exception {
    NimbusPayloadSigner signer = new NimbusPayloadSigner(new JwkSigningKeys(writeKeys(ec("k"))));
    String jwks = signer.publicJwks();
    byte[] c2 =
        new String(evt12(), StandardCharsets.UTF_8)
            .replace("\"classification\":\"C1\"", "\"classification\":\"C2\"")
            .getBytes(StandardCharsets.UTF_8);
    CloudEventVerifier plain = new CloudEventVerifier(jwks, Map.of());
    assertThatThrownBy(() -> plain.verifyDetached(c2, signer.signDetached(c2)))
        .isInstanceOfSatisfying(
            VerificationException.class,
            e -> assertThat(e.problems()).anyMatch(p -> p.contains("classification")));
    byte[] notJson = "x".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> plain.verifyDetached(notJson, signer.signDetached(notJson)))
        .isInstanceOf(VerificationException.class);

    String dataSchema =
        """
        {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
         "required":["rating"],"properties":{"rating":{"enum":["HTXSNV","HTTNV","HTNV","KHTNV"]}}}
        """;
    String type = "vn.viettel.okrkpi." + CloudEventsMapperTest.TOPIC;
    CloudEventVerifier strict = new CloudEventVerifier(jwks, Map.of(type, dataSchema));
    byte[] good = evt12();
    strict.verifyDetached(good, signer.signDetached(good));
    byte[] badRating =
        new String(good, StandardCharsets.UTF_8)
            .replace("\"HTTNV\"", "\"TOT\"")
            .getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> strict.verifyDetached(badRating, signer.signDetached(badRating)))
        .isInstanceOfSatisfying(
            VerificationException.class,
            e -> assertThat(e.problems()).anyMatch(p -> p.contains("rating")));
    assertThatThrownBy(() -> new CloudEventVerifier("{", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unconfiguredSignerRefusesToSign() {
    CloudEventsConfiguration.UnconfiguredSigner s =
        CloudEventsConfiguration.UnconfiguredSigner.INSTANCE;
    assertThat(s.publicJwks()).isEqualTo("{\"keys\":[]}");
    assertThatThrownBy(() -> s.signDetached(new byte[0])).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> s.signCompact(new byte[0])).isInstanceOf(IllegalStateException.class);
  }
}
