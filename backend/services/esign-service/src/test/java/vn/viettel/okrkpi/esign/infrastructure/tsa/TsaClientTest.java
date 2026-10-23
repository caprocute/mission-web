package vn.viettel.okrkpi.esign.infrastructure.tsa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.esign.application.sign.Hashes;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/** Client dấu thời gian RFC 3161 với TSA giả lập (DV-10-03). */
class TsaClientTest {

  static TsaMockServer primary;
  static TsaMockServer backup;
  static ResilientClientFactory factory;

  @BeforeAll
  static void start() throws Exception {
    primary = new TsaMockServer();
    backup = new TsaMockServer();
    factory = factory();
  }

  @AfterAll
  static void stop() {
    primary.close();
    backup.close();
  }

  static ResilientClientFactory factory() {
    return new ResilientClientFactory(
        new StaticListableBeanFactory().getBeanProvider(RestClient.Builder.class),
        CircuitBreakerRegistry.ofDefaults(),
        RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()),
        TimeLimiterRegistry.of(
            TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(5)).build()),
        BulkheadRegistry.ofDefaults());
  }

  static TsaClient.Endpoint endpoint(String name, TsaMockServer s) {
    return new TsaClient.Endpoint(s.url(), factory.create(name, s.url()));
  }

  @Test
  void tokenKiemDuocBangOpenssl(@TempDir Path dir) throws Exception {
    assumeTrue(onPath("openssl"), "máy build không có openssl");
    byte[] data = "goc-merkle".getBytes(StandardCharsets.UTF_8);
    TsaClient client = new TsaClient(List.of(endpoint("tsa-a", primary)), List.of(primary.root));
    byte[] token = client.timestamp(data);
    Path tok = Files.write(dir.resolve("token.der"), token);
    Path ca = Files.writeString(dir.resolve("ca.pem"), pem(primary.root.getEncoded()));
    String out =
        run(
            "openssl",
            "ts",
            "-verify",
            "-digest",
            HexFormat.of().formatHex(Hashes.sha256(data)),
            "-in",
            tok.toString(),
            "-token_in",
            "-CAfile",
            ca.toString());
    assertThat(out).contains("Verification: OK");
  }

  @Test
  void tsaChinhLoiThiChuyenTsaDuPhong() {
    TsaClient client =
        new TsaClient(
            List.of(endpoint("tsa-b1", primary), endpoint("tsa-b2", backup)),
            List.of(primary.root, backup.root));
    primary.failNext.set(1);
    int before = backup.calls.get();
    assertThat(client.timestampDigest(new byte[32])).isNotEmpty();
    assertThat(backup.calls.get()).isEqualTo(before + 1);
  }

  @Test
  void tuChoiTokenCuaTsaNgoaiNeoTinCay() {
    TsaClient client = new TsaClient(List.of(endpoint("tsa-c", primary)), List.of(backup.root));
    assertThatThrownBy(() -> client.timestampDigest(new byte[32]))
        .isInstanceOf(SignatureProviderException.class)
        .hasMessageContaining("Không lấy được dấu thời gian");
  }

  @Test
  void neoLaChinhChungThuTsaCungDuocChapNhan() {
    TsaClient client = new TsaClient(List.of(endpoint("tsa-d", primary)), List.of(primary.tsaCert));
    assertThat(client.timestampDigest(new byte[32])).isNotEmpty();
  }

  @Test
  void dauVaoVaCauHinhSaiBiTuChoi() {
    TsaClient client = new TsaClient(List.of(endpoint("tsa-e", primary)), List.of(primary.root));
    assertThatThrownBy(() -> client.timestampDigest(new byte[31]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.timestampDigest(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TsaClient(List.of(), List.of(primary.root)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TsaClient(List.of(endpoint("tsa-f", primary)), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.verify(null, null))
        .isInstanceOf(SignatureProviderException.class);
  }

  @Test
  void napNeoTinCayTuTepPem(@TempDir Path dir) throws Exception {
    Path ca = Files.writeString(dir.resolve("ca.pem"), pem(primary.root.getEncoded()));
    assertThat(TsaConfiguration.loadAnchors(List.of(ca.toString()))).containsExactly(primary.root);
    assertThatThrownBy(() -> TsaConfiguration.loadAnchors(List.of(dir.resolve("x").toString())))
        .isInstanceOf(IllegalStateException.class);
  }

  static String pem(byte[] der) {
    return "-----BEGIN CERTIFICATE-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
        + "\n-----END CERTIFICATE-----\n";
  }

  static boolean onPath(String tool) {
    for (String d : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
      if (Files.isExecutable(Path.of(d, tool))) {
        return true;
      }
    }
    return false;
  }

  static String run(String... command) throws Exception {
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    p.waitFor();
    return out;
  }
}
