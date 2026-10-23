package vn.viettel.okrkpi.esign.infrastructure.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Ký tổ chức qua cổng nội bộ (DV-13-10): chữ ký trên băm SHA-256 kiểm được bằng khoá công khai trả
 * về như chữ ký {@code SHA256withECDSA} trên tài liệu gốc; sai vai trò bị chặn; mục đích lạ hoặc
 * băm sai cỡ bị từ chối.
 */
@SpringBootTest(
    properties = {
      "okrkpi.esign.org-seal.enabled=true",
      "okrkpi.esign.org-seal.provider=LOCAL",
      "okrkpi.esign.worker.enabled=false",
      "management.server.port=8080"
    })
@AutoConfigureMockMvc
@Testcontainers
class OrgSealIT {

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
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair pair = ec.generateKeyPair();
    Path dir = Files.createTempDirectory("org-seal");
    Path priv = dir.resolve("seal.key");
    Path pub = dir.resolve("seal.pub");
    Files.writeString(priv, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
    Files.writeString(pub, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
    registry.add("okrkpi.esign.org-seal.private-key-path", priv::toString);
    registry.add("okrkpi.esign.org-seal.public-key-path", pub::toString);
  }

  static String pem(String type, byte[] der) {
    return "-----BEGIN "
        + type
        + "-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
        + "\n-----END "
        + type
        + "-----\n";
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Test
  void sealVerifiesAsSignatureOnDocument() throws Exception {
    byte[] header = "{\"packageId\":\"x\"}".getBytes(StandardCharsets.UTF_8);
    String sha =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(header));
    String out =
        call("svc_integration_gateway", "GOI_CHUYEN_LIEN_CELL", sha)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode body = json.readTree(out);
    assertThat(body.path("algorithm").asText()).isEqualTo("SHA256withECDSA");
    PublicKey key =
        KeyFactory.getInstance("EC")
            .generatePublic(
                new X509EncodedKeySpec(
                    Base64.getDecoder().decode(body.path("publicKey").asText())));
    Signature v = Signature.getInstance("SHA256withECDSA");
    v.initVerify(key);
    v.update(header);
    assertThat(v.verify(Base64.getDecoder().decode(body.path("signature").asText()))).isTrue();

    call("tenant_admin", "GOI_CHUYEN_LIEN_CELL", sha).andExpect(status().isForbidden());
    call("svc_integration_gateway", "KY_BAT_KY", sha).andExpect(status().isUnprocessableEntity());
    call("svc_integration_gateway", "GOI_CHUYEN_LIEN_CELL", "AAAA")
        .andExpect(status().isUnprocessableEntity());
    call("svc_integration_gateway", "GOI_CHUYEN_LIEN_CELL", "khong-phai-base64!")
        .andExpect(status().isUnprocessableEntity());
  }

  private ResultActions call(String role, String purpose, String sha) throws Exception {
    return mvc.perform(
        post("/internal/v1/esign/org-signatures")
            .with(
                jwt()
                    .jwt(
                        j ->
                            j.claim("azp", "integration-gateway")
                                .claim("preferred_username", "service-account-integration-gateway"))
                    .authorities(new SimpleGrantedAuthority("ROLE_" + role)))
            .header("X-Tenant-Id", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"purpose\":\""
                    + purpose
                    + "\",\"reference\":\"goi:"
                    + UUID.randomUUID()
                    + "\",\"sha256\":\""
                    + sha
                    + "\"}"));
  }
}
