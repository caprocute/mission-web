package vn.viettel.okrkpi.esign.infrastructure.provider.hsm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.tsp.TimeStampToken;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.esign.infrastructure.tsa.TsaMockServer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;

/**
 * {@code POST /api/v1/esign/seals} (DV-10-03) trên ứng dụng thật với SoftHSM2 và TSA giả lập: dịch
 * vụ sổ cái nhận chữ ký ECDSA P-256 trên gốc Merkle và token RFC 3161 có imprint là chính gốc; bên
 * gọi khác bị từ chối; readiness có chỉ báo {@code hsm}. Bỏ qua khi máy build không cài softhsm2.
 */
@SpringBootTest(
    properties = {
      "okrkpi.esign.worker.enabled=false",
      "okrkpi.esign.hsm.enabled=true",
      "okrkpi.esign.hsm.provision-dev-keys=true",
      "okrkpi.esign.tsa.enabled=true",
      "management.server.port=8080"
    })
@AutoConfigureMockMvc
@Testcontainers
@EnabledIf("vn.viettel.okrkpi.esign.infrastructure.provider.hsm.SoftHsm#available")
class LedgerSealApiIT {

  static final String ROOT = HexFormat.of().formatHex(Hashes.sha256("lo-24h".getBytes()));
  static TsaMockServer tsa;

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("mission_esign")
          .withUsername("postgres")
          .withPassword("postgres");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) throws Exception {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    SoftHsm.Token token = SoftHsm.newToken();
    registry.add("okrkpi.esign.hsm.library", SoftHsm::library);
    registry.add("okrkpi.esign.hsm.slot", token::slot);
    registry.add("okrkpi.esign.hsm.pin", token::pin);
    tsa = new TsaMockServer();
    Path anchor = Files.createTempFile("tsa-root", ".pem");
    Files.writeString(
        anchor,
        "-----BEGIN CERTIFICATE-----\n"
            + Base64.getMimeEncoder().encodeToString(tsa.root.getEncoded())
            + "\n-----END CERTIFICATE-----\n");
    registry.add("okrkpi.esign.tsa.urls", tsa::url);
    registry.add("okrkpi.esign.tsa.trust-anchors", anchor::toString);
  }

  @AfterAll
  static void stop() {
    if (tsa != null) {
      tsa.close();
    }
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    OutboxPayloadSerializer fakeSerializer() {
      return (topic, payload) ->
          new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private HsmPkcs11Signer signer;
  @Autowired private HsmProperties properties;

  static MockHttpServletRequestBuilder seal(String client, String root) {
    return post("/api/v1/esign/seals")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"sealDate":"2026-10-23","merkleRoot":"%s","prevMerkleRoot":null,"eventCount":128}
            """
                .formatted(root))
        .with(
            jwt()
                .jwt(
                    j ->
                        j.subject(UUID.randomUUID().toString())
                            .claim("azp", client)
                            .claim("tenant_id", "0192f1a2-0000-7000-8000-00000000f001")));
  }

  @Test
  void soCaiNhanChuKyHsmVaDauThoiGianTrenGoc() throws Exception {
    String body =
        mvc.perform(seal("ledger-service", ROOT))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.merkleRoot").value(ROOT))
            .andExpect(jsonPath("$.sealDate").value("2026-10-23"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    JsonNode r = json.readTree(body);
    X509Certificate cert = signer.chain(properties.sealKeyAlias()).get(0);
    Signature verify = Signature.getInstance("SHA256withECDSA");
    verify.initVerify(cert.getPublicKey());
    verify.update(HexFormat.of().parseHex(ROOT));
    assertThat(verify.verify(Base64.getDecoder().decode(r.get("signatureB64").asText()))).isTrue();
    assertThat(r.get("certificateSerial").asText()).isEqualTo(cert.getSerialNumber().toString(16));
    TimeStampToken token =
        new TimeStampToken(
            new CMSSignedData(Base64.getDecoder().decode(r.get("tsaTokenB64").asText())));
    assertThat(token.getTimeStampInfo().getMessageImprintDigest())
        .isEqualTo(HexFormat.of().parseHex(ROOT));
  }

  @Test
  void benGoiKhacSoCaiBiTuChoi() throws Exception {
    mvc.perform(seal("okr-core", ROOT)).andExpect(status().isForbidden());
  }

  @Test
  void gocSaiDangBiTuChoi() throws Exception {
    mvc.perform(seal("ledger-service", "XYZ")).andExpect(status().isUnprocessableEntity());
  }

  @Test
  void readinessCoChiBaoHsm() throws Exception {
    mvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.hsm.status").value("UP"));
  }
}
