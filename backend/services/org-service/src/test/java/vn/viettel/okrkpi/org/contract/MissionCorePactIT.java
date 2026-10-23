package vn.viettel.okrkpi.org.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;
import vn.viettel.okrkpi.org.support.PostgresSupport;
import vn.viettel.okrkpi.org.support.TestJwt;

/**
 * QA-02 · bên cung cấp service với service: org-service (SVC-04) xác minh hợp đồng mà mission-core
 * công bố ở {@code backend/mission-core/src/test/resources/pacts/mission-core-org-service.json}
 * (khoá phục hồi E2EE, token dịch vụ uỷ quyền tenant qua {@code X-Tenant-Id}). Header
 * {@code Authorization} giả lập của hợp đồng được thay bằng token dịch vụ ký bằng khoá kiểm thử.
 */
@AutoConfigureMockMvc
class MissionCorePactIT extends IntegrationTestBase {

  static final Path PACT = Path.of("../../mission-core/src/test/resources/pacts/mission-core-org-service.json");
  static final UUID SYSTEM = UUID.fromString("00000000-0000-0000-0000-000000000000");

  @Autowired
  MockMvc mvc;

  @Test
  void missionCoreDungHopDongVoiOrgService() {
    Pact pact = Pact.load(PACT);
    assertThat(pact.consumer()).isEqualTo("mission-core");
    assertThat(pact.provider()).isEqualTo("org-service");
    assertThat(verifier().failures(pact)).isEmpty();
  }

  @Test
  void doiTenTruongMissionCoreDocThiXacMinhDo() {
    var raw = Pact.load(PACT).copyRaw();
    var body = (com.fasterxml.jackson.databind.node.ObjectNode) raw.at("/interactions/0/response/body");
    body.set("keyFingerprint", body.remove("fingerprint"));
    assertThat(verifier().failures(Pact.of(raw))).anySatisfy(e -> assertThat(e).contains("$.keyFingerprint: thiếu"));
  }

  private MockMvcPactVerifier verifier() {
    String token = "Bearer " + TestJwt.serviceToken("mission-core", List.of("service_client"));
    return new MockMvcPactVerifier(mvc, Map.of("Authorization", token), this::state);
  }

  private Callable<MvcResult> state(Pact.State s, Callable<MvcResult> request) throws Exception {
    UUID tenant = UUID.fromString(s.params().path("tenantId").asText());
    try (Connection c = PostgresSupport.admin()) {
      try (PreparedStatement ps = c.prepareStatement("DELETE FROM org.recovery_key WHERE tenant_id = ?")) {
        ps.setObject(1, tenant);
        ps.executeUpdate();
      }
      switch (s.name()) {
        case "tenant có khoá phục hồi hiện hành" -> recoveryKey(c, tenant,
            UUID.fromString(s.params().path("keyId").asText()));
        case "tenant chưa có khoá phục hồi" -> {
        }
        default -> throw new IllegalArgumentException("Chưa có trạng thái: " + s.name());
      }
    }
    return request;
  }

  private static void recoveryKey(Connection c, UUID tenant, UUID id) throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(256);
    byte[] spki = g.generateKeyPair().getPublic().getEncoded();
    Array custodians = c.createArrayOf("uuid", new Object[] {UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()});
    try (PreparedStatement ps = c.prepareStatement("INSERT INTO org.recovery_key (id, tenant_id, key_alg, "
        + "public_key_spki, fingerprint, threshold, custodian_ids, ceremony_ref, status, valid_from, "
        + "sig_key_version, signature, signed_at, created_by, updated_by) VALUES (?, ?, 'ECDH-P256', ?, ?, 3, ?, "
        + "'BB-01/2026', 'HIEU_LUC', now(), 1, ?, now(), ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setBytes(3, spki);
      ps.setBytes(4, MessageDigest.getInstance("SHA-256").digest(spki));
      ps.setArray(5, custodians);
      ps.setBytes(6, new byte[] {1});
      ps.setObject(7, SYSTEM);
      ps.setObject(8, SYSTEM);
      ps.executeUpdate();
    }
  }
}
