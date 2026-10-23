package vn.viettel.okrkpi.org.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import vn.viettel.okrkpi.org.application.port.DirectorySigner;
import vn.viettel.okrkpi.org.application.port.IntegrationUnavailableException;

/**
 * CORE-07-06 phần A: ký danh bạ khoá bằng OpenBao Transit thật (chế độ dev trong Testcontainers). Chữ ký DER kiểm lại
 * được bằng khoá công khai PEM mà {@code listE2eeDirectoryKeys} trả; mỗi tenant một khoá ký; thiếu khoá khi không bật
 * tự tạo, hoặc sai token, trả lỗi không ký được (503).
 */
class OpenBaoDirectorySignerIT {

  private static final String ROOT = "kiem-thu-" + UUID.randomUUID();
  private static GenericContainer<?> bao;
  private static URI base;

  @TempDir
  static Path dir;

  @BeforeAll
  static void start() throws Exception {
    bao = new GenericContainer<>("quay.io/openbao/openbao:2.1.0")
        .withCommand("server", "-dev", "-dev-root-token-id=" + ROOT, "-dev-listen-address=0.0.0.0:8200")
        .withExposedPorts(8200)
        .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));
    bao.start();
    base = URI.create("http://" + bao.getHost() + ":" + bao.getMappedPort(8200));
    var res = bao.execInContainer("sh", "-c",
        "BAO_ADDR=http://127.0.0.1:8200 BAO_TOKEN=" + ROOT + " bao secrets enable transit");
    assertThat(res.getExitCode()).as(res.getStderr()).isZero();
  }

  @AfterAll
  static void stop() {
    if (bao != null) {
      bao.stop();
    }
  }

  @Test
  void kyVaKiemDuocChuKyTheoTungTenant() throws Exception {
    DirectorySigner signer = signer(token(ROOT), true);
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    byte[] payload = ("v1|" + tenantA + "|PERSON_KEY|...").getBytes(StandardCharsets.UTF_8);

    DirectorySigner.Signed signed = signer.sign(tenantA, payload);
    assertThat(signed.keyVersion()).isEqualTo(1);
    List<DirectorySigner.VerificationKey> keysA = signer.verificationKeys(tenantA);
    assertThat(keysA).hasSize(1);
    assertThat(verify(keysA.get(0).publicKeyPem(), payload, signed.signature())).isTrue();
    assertThat(verify(keysA.get(0).publicKeyPem(), "khac".getBytes(StandardCharsets.UTF_8), signed.signature()))
        .isFalse();

    signer.sign(tenantB, payload);
    String pemB = signer.verificationKeys(tenantB).get(0).publicKeyPem();
    assertThat(pemB).isNotEqualTo(keysA.get(0).publicKeyPem());
    assertThat(verify(pemB, payload, signed.signature())).isFalse();
  }

  @Test
  void khongTuTaoKhoaHoacSaiTokenThiKhongKyDuoc() throws Exception {
    assertThatThrownBy(() -> signer(token(ROOT), false).sign(UUID.randomUUID(), new byte[] {1}))
        .isInstanceOf(IntegrationUnavailableException.class);
    UUID tenant = UUID.randomUUID();
    signer(token(ROOT), true).sign(tenant, new byte[] {1});
    assertThatThrownBy(() -> signer(token("sai-token"), true).sign(tenant, new byte[] {1}))
        .isInstanceOf(IntegrationUnavailableException.class);
  }

  private static OpenBaoDirectorySigner signer(Path tokenFile, boolean autoCreate) {
    return new OpenBaoDirectorySigner(HttpClient.newHttpClient(), new ObjectMapper(), base, "transit",
        "org-directory-", tokenFile, autoCreate);
  }

  private static Path token(String value) throws Exception {
    Path f = Files.createTempFile(dir, "token", "");
    Files.writeString(f, value + "\n");
    return f;
  }

  private static boolean verify(String pem, byte[] payload, byte[] der) throws Exception {
    String b64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder()
        .decode(b64)));
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initVerify(key);
    s.update(payload);
    return s.verify(der);
  }
}
