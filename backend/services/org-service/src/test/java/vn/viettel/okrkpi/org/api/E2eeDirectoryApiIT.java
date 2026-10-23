package vn.viettel.okrkpi.org.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.org.application.port.DirectorySigner;
import vn.viettel.okrkpi.org.support.ContractValidator;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;
import vn.viettel.okrkpi.org.support.TestJwt;

/**
 * CORE-07-06 phần A, D · ADR-27: danh bạ khoá công khai có ký, khoá phục hồi tổ chức và nhật ký phục hồi trên
 * PostgreSQL thật, đối chiếu hợp đồng {@code org.yaml}. Bộ ký OpenBao được thay bằng khoá ECDSA P-256 trong bộ nhớ để
 * kiểm chữ ký trên chuỗi chuẩn; bộ ký OpenBao thật kiểm ở {@code OpenBaoDirectorySignerIT}.
 */
@AutoConfigureMockMvc
@Import(E2eeDirectoryApiIT.Signer.class)
class E2eeDirectoryApiIT extends IntegrationTestBase {

  static final KeyPair DIRECTORY = ec("secp256r1");
  static final List<String> ALL = List.of("member");

  @Autowired
  MockMvc mvc;
  @Autowired
  ObjectMapper json;

  private final Fixtures fx = new Fixtures();
  private UUID tenant;
  private UUID owner;
  private UUID other;
  private UUID admin;
  private List<UUID> custodians;

  @BeforeEach
  void setUp() {
    tenant = UUID.randomUUID();
    owner = fx.person(tenant, "Nguyễn Văn An");
    other = fx.person(tenant, "Trần Thị Bình");
    admin = fx.person(tenant, "Lê Văn Cường");
    custodians = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      custodians.add(fx.person(tenant, "Người giữ mảnh " + (i + 1)));
    }
  }

  @Test
  void dangKyKhoaThietBiKyBanGhiGhiSoCaiVaChongTrung() throws Exception {
    KeyPair device = ec("secp256r1");
    String spki = Base64.getEncoder().encodeToString(device.getPublic().getEncoded());
    String body = "{\"keyAlg\":\"ECDH-P256\",\"publicKeySpki\":\"" + spki + "\",\"source\":\"THIET_BI\"}";
    call(post("/api/v1/org/persons/me/public-keys").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(body), owner, ALL, "aal1")
        .andExpect(status().isUnauthorized());

    String key = UUID.randomUUID().toString();
    JsonNode first = null;
    for (int i = 0; i < 2; i++) {
      MvcResult r = call(post("/api/v1/org/persons/me/public-keys").header("Idempotency-Key", key)
          .contentType(MediaType.APPLICATION_JSON).content(body), owner, ALL, "aal2")
          .andExpect(status().isCreated())
          .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
          .andExpect(jsonPath("$.status").value("HIEU_LUC"))
          .andReturn();
      JsonNode node = json.readTree(r.getResponse().getContentAsString());
      if (first == null) {
        first = node;
      } else {
        assertThat(node.path("id").asText()).isEqualTo(first.path("id").asText());
      }
    }
    String fp = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(device.getPublic().getEncoded()));
    assertThat(first.path("fingerprint").asText()).isEqualTo(fp);
    assertThat(first.path("personId").asText()).isEqualTo(owner.toString());
    String canonical = String.join("|", "v1", tenant.toString(), "PERSON_KEY", first.path("id").asText(),
        owner.toString(), "ECDH-P256", fp, Instant.parse(first.path("validFrom").asText()).toString(),
        Instant.parse(first.path("validTo").asText()).toString());
    assertThat(verify(canonical, first.path("signature").path("value").asText())).isTrue();
    assertThat(Instant.parse(first.path("validTo").asText()))
        .isAfter(Instant.now().plus(1094, ChronoUnit.DAYS));
    assertThat(fx.outboxCount(tenant, "sys.audit.v1")).isEqualTo(1);

    call(post("/api/v1/org/persons/me/public-keys").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(body), other, ALL, "aal2")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORG-409-PUBLIC-KEY-EXISTS"));
  }

  @Test
  void khoaSaiThuatToanBiTuChoi() throws Exception {
    String p384 = Base64.getEncoder().encodeToString(ec("secp384r1").getPublic().getEncoded());
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    String rsa2048 = Base64.getEncoder().encodeToString(rsa.generateKeyPair().getPublic().getEncoded());
    for (String body : List.of(
        "{\"keyAlg\":\"ECDH-P256\",\"publicKeySpki\":\"" + p384 + "\",\"source\":\"THIET_BI\"}",
        "{\"keyAlg\":\"RSA-OAEP-3072\",\"publicKeySpki\":\"" + rsa2048 + "\",\"source\":\"CHUNG_THU\"}",
        "{\"keyAlg\":\"ECDH-P256\",\"publicKeySpki\":\"khong-phai-base64\",\"source\":\"THIET_BI\"}")) {
      call(post("/api/v1/org/persons/me/public-keys").header("Idempotency-Key", UUID.randomUUID().toString())
          .contentType(MediaType.APPLICATION_JSON).content(body), owner, ALL, "aal2")
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.errors[0].field").value("publicKeySpki"));
    }
    assertThat(fx.outboxCount(tenant, "sys.audit.v1")).isZero();
  }

  @Test
  void traCuuVaThuHoiKhoaTheoChuKhoa() throws Exception {
    JsonNode k = register(owner);
    String id = k.path("id").asText();
    call(get("/api/v1/org/persons/" + owner + "/public-keys"), other, ALL, "aal1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(id));
    UUID foreign = UUID.randomUUID();
    call(get("/api/v1/org/persons/" + owner + "/public-keys"), fx.person(foreign, "Người tenant khác"), ALL, "aal1",
        foreign).andExpect(jsonPath("$.items.length()").value(0));

    String revoke = "{\"reason\":\"Mất điện thoại công vụ\"}";
    call(post("/api/v1/org/public-keys/" + id + "/revoke").header("Idempotency-Key", UUID.randomUUID().toString())
        .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON).content(revoke),
        other, ALL, "aal2").andExpect(status().isForbidden());
    call(post("/api/v1/org/public-keys/" + id + "/revoke").header("Idempotency-Key", UUID.randomUUID().toString())
        .header(HttpHeaders.IF_MATCH, "\"5\"").contentType(MediaType.APPLICATION_JSON).content(revoke),
        owner, ALL, "aal2").andExpect(status().isConflict());
    call(post("/api/v1/org/public-keys/" + id + "/revoke").header("Idempotency-Key", UUID.randomUUID().toString())
        .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON).content(revoke),
        owner, ALL, "aal2")
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
        .andExpect(jsonPath("$.status").value("THU_HOI"));
    call(post("/api/v1/org/public-keys/" + id + "/revoke").header("Idempotency-Key", UUID.randomUUID().toString())
        .header(HttpHeaders.IF_MATCH, "\"1\"").contentType(MediaType.APPLICATION_JSON).content(revoke),
        admin, List.of("tenant_admin"), "aal2").andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("THU_HOI"));

    call(get("/api/v1/org/persons/" + owner + "/public-keys"), other, ALL, "aal1")
        .andExpect(jsonPath("$.items.length()").value(0));
    call(get("/api/v1/org/persons/" + owner + "/public-keys?includeInactive=true"), other, ALL, "aal1")
        .andExpect(jsonPath("$.items[0].status").value("THU_HOI"));
    assertThat(fx.outboxCount(tenant, "sys.audit.v1")).isEqualTo(2);
  }

  @Test
  void khoaPhucHoiThayKhoaVaNhatKyPhucHoi() throws Exception {
    call(get("/api/v1/org/e2ee/recovery-key"), owner, ALL, "aal1").andExpect(status().isNotFound());
    call(post("/api/v1/org/e2ee/recovery-keys").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(recoveryKeyBody(custodians)), owner, ALL, "aal2")
        .andExpect(status().isForbidden());
    call(post("/api/v1/org/e2ee/recovery-keys").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(recoveryKeyBody(custodians.subList(0, 4))), admin,
        List.of("tenant_admin"), "aal2").andExpect(status().isUnprocessableEntity());
    List<UUID> outsiders = new ArrayList<>(custodians.subList(0, 4));
    outsiders.add(UUID.randomUUID());
    call(post("/api/v1/org/e2ee/recovery-keys").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(recoveryKeyBody(outsiders)), admin,
        List.of("tenant_admin"), "aal2").andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].field").value("custodianIds"));

    JsonNode oldKey = rotate();
    JsonNode newKey = rotate();
    MvcResult current = call(get("/api/v1/org/e2ee/recovery-key"), owner, ALL, "aal1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(newKey.path("id").asText()))
        .andExpect(jsonPath("$.threshold").value(3))
        .andExpect(jsonPath("$.status").value("HIEU_LUC"))
        .andReturn();
    JsonNode k = json.readTree(current.getResponse().getContentAsString());
    String canonical = String.join("|", "v1", tenant.toString(), "RECOVERY_KEY", k.path("id").asText(), "",
        "ECDH-P256", k.path("fingerprint").asText(), Instant.parse(k.path("validFrom").asText()).toString(), "");
    assertThat(verify(canonical, k.path("signature").path("value").asText())).isTrue();
    assertThat(fx.admin().queryForObject("SELECT status FROM org.recovery_key WHERE id = ?", String.class,
        UUID.fromString(oldKey.path("id").asText()))).isEqualTo("THU_HOI");

    JsonNode device = register(other);
    UUID object = UUID.randomUUID();
    String fp = device.path("fingerprint").asText();
    String two = recovery(newKey, object, other, fp, custodians.subList(0, 2));
    call(post("/api/v1/org/e2ee/recoveries").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(two), admin, List.of("tenant_admin"), "aal2")
        .andExpect(status().isUnprocessableEntity());
    List<UUID> wrongShares = List.of(custodians.get(0), custodians.get(1), owner);
    call(post("/api/v1/org/e2ee/recoveries").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(recovery(newKey, object, other, fp, wrongShares)), admin,
        List.of("tenant_admin"), "aal2").andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].field").value("custodianIds"));
    call(post("/api/v1/org/e2ee/recoveries").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(recovery(newKey, object, owner, fp,
            custodians.subList(0, 3))), admin, List.of("tenant_admin"), "aal2")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].field").value("newKeyFingerprint"));
    call(post("/api/v1/org/e2ee/recoveries").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(recovery(newKey, object, other, fp,
            custodians.subList(1, 4))), admin, List.of("tenant_admin"), "aal2")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.recordedBy").value(admin.toString()));
    call(post("/api/v1/org/e2ee/recoveries").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(recovery(newKey, UUID.randomUUID(), other, fp,
            custodians.subList(2, 5))), admin, List.of("tenant_admin"), "aal2")
        .andExpect(status().isCreated());

    call(get("/api/v1/org/e2ee/recoveries"), owner, ALL, "aal2").andExpect(status().isForbidden());
    MvcResult page = call(get("/api/v1/org/e2ee/recoveries?limit=1"), other, List.of("inspector"), "aal2")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andReturn();
    String cursor = json.readTree(page.getResponse().getContentAsString()).path("nextCursor").asText();
    call(get("/api/v1/org/e2ee/recoveries?limit=1&cursor=" + cursor), other, List.of("inspector"), "aal2")
        .andExpect(jsonPath("$.items[0].objectId").value(object.toString()))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
    call(get("/api/v1/org/e2ee/recoveries?objectId=" + object), admin, List.of("tenant_admin"), "aal2")
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].custodianIds.length()").value(3));
    // 2 lần thay khoá phục hồi + 1 khoá thiết bị + 2 lần phục hồi.
    assertThat(fx.outboxCount(tenant, "sys.audit.v1")).isEqualTo(5);
  }

  private JsonNode register(UUID person) throws Exception {
    String spki = Base64.getEncoder().encodeToString(ec("secp256r1").getPublic().getEncoded());
    MvcResult r = call(post("/api/v1/org/persons/me/public-keys")
        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"keyAlg\":\"ECDH-P256\",\"publicKeySpki\":\"" + spki + "\",\"source\":\"THIET_BI\"}"),
        person, ALL, "aal2").andExpect(status().isCreated()).andReturn();
    return json.readTree(r.getResponse().getContentAsString());
  }

  private JsonNode rotate() throws Exception {
    MvcResult r = call(post("/api/v1/org/e2ee/recovery-keys").header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON).content(recoveryKeyBody(custodians)), admin,
        List.of("tenant_admin"), "aal2").andExpect(status().isCreated()).andReturn();
    return json.readTree(r.getResponse().getContentAsString());
  }

  private static String recoveryKeyBody(List<UUID> ids) {
    String spki = Base64.getEncoder().encodeToString(ec("secp256r1").getPublic().getEncoded());
    return "{\"keyAlg\":\"ECDH-P256\",\"publicKeySpki\":\"" + spki + "\",\"custodianIds\":" + array(ids)
        + ",\"ceremonyRef\":\"BB-01/2026\"}";
  }

  private static String recovery(JsonNode key, UUID object, UUID recipient, String fp, List<UUID> present) {
    return "{\"recoveryKeyId\":\"" + key.path("id").asText() + "\",\"objectType\":\"TASK_CONTENT\",\"objectId\":\""
        + object + "\",\"newRecipientId\":\"" + recipient + "\",\"newKeyFingerprint\":\"" + fp
        + "\",\"custodianIds\":" + array(present) + ",\"reason\":\"Cán bộ mất thiết bị, cần mở lại nhiệm vụ mật\","
        + "\"ceremonyRef\":\"BB-PH-02/2026\",\"performedAt\":\"" + Instant.now() + "\"}";
  }

  private static String array(List<UUID> ids) {
    return ids.stream().map(i -> "\"" + i + "\"").collect(Collectors.joining(",", "[", "]"));
  }

  private static boolean verify(String canonical, String base64Signature) throws Exception {
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initVerify(DIRECTORY.getPublic());
    s.update(canonical.getBytes(StandardCharsets.UTF_8));
    return s.verify(Base64.getDecoder().decode(base64Signature));
  }

  private ResultActions call(MockHttpServletRequestBuilder req, UUID subject, List<String> roles, String acr)
      throws Exception {
    return call(req, subject, roles, acr, tenant);
  }

  private ResultActions call(MockHttpServletRequestBuilder req, UUID subject, List<String> roles, String acr,
      UUID inTenant) throws Exception {
    String token = "Bearer " + TestJwt.token(inTenant, subject, roles, acr, null);
    ResultActions r = mvc.perform(req.header(HttpHeaders.AUTHORIZATION, token));
    MvcResult result = r.andReturn();
    ValidationReport report = ContractValidator.validate(result);
    assertThat(report.getMessages()).as(result.getResponse().getContentAsString()).isEmpty();
    return r;
  }

  static KeyPair ec(String curve) {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec(curve));
      return g.generateKeyPair();
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @TestConfiguration
  static class Signer {
    @Bean
    @Primary
    DirectorySigner testDirectorySigner() {
      return new DirectorySigner() {
        @Override
        public Signed sign(UUID tenantId, byte[] payload) {
          try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(DIRECTORY.getPrivate());
            s.update(payload);
            return new Signed(1, s.sign());
          } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
          }
        }

        @Override
        public List<VerificationKey> verificationKeys(UUID tenantId) {
          return List.of(new VerificationKey(1, "-----BEGIN PUBLIC KEY-----\n"
              + Base64.getMimeEncoder().encodeToString(DIRECTORY.getPublic().getEncoded())
              + "\n-----END PUBLIC KEY-----\n"));
        }
      };
    }
  }
}
