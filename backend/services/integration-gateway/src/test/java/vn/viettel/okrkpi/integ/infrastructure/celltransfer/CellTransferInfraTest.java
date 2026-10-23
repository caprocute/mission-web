package vn.viettel.okrkpi.integ.infrastructure.celltransfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.integ.application.port.PackageCrypto;
import vn.viettel.okrkpi.integ.application.port.TransferCodec;
import vn.viettel.okrkpi.integ.application.port.TransferCodec.Part;
import vn.viettel.okrkpi.integ.infrastructure.crypto.JcaPackageCrypto;
import vn.viettel.okrkpi.integ.infrastructure.crypto.PemKeys;

/** Danh bạ khoá cell có ký, định dạng gói và mật mã gói (DV-13-10). */
class CellTransferInfraTest {

  static final ObjectMapper JSON = new ObjectMapper();
  static KeyPair root;
  static KeyPair sign;
  static KeyPair box;

  @BeforeAll
  static void keys() throws Exception {
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp256r1"));
    root = ec.generateKeyPair();
    sign = ec.generateKeyPair();
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(3072);
    box = rsa.generateKeyPair();
  }

  static byte[] directory(long version, Instant now, String endpoint, KeyPair signer)
      throws Exception {
    Base64.Encoder b64 = Base64.getEncoder();
    ObjectNode doc =
        JSON.createObjectNode()
            .put("version", version)
            .put("expiresAt", now.plusSeconds(600).toString());
    doc.putArray("cells")
        .addObject()
        .put("cell", "HN")
        .put("endpoint", endpoint)
        .put("signingKey", b64.encodeToString(sign.getPublic().getEncoded()))
        .put("encryptionKey", b64.encodeToString(box.getPublic().getEncoded()))
        .put("notBefore", now.minusSeconds(60).toString())
        .put("notAfter", now.plusSeconds(300).toString());
    byte[] payload = JSON.writeValueAsBytes(doc);
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(signer.getPrivate());
    s.update(payload);
    return JSON.writeValueAsBytes(
        Map.of("payload", b64.encodeToString(payload), "signature", b64.encodeToString(s.sign())));
  }

  static Clock clock(AtomicReference<Instant> now) {
    return new Clock() {
      @Override
      public ZoneOffset getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(java.time.ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        return now.get();
      }
    };
  }

  @Test
  void directoryTrustsOnlySignedFreshDocuments() throws Exception {
    Instant t0 = Instant.parse("2026-10-23T08:00:00Z");
    AtomicReference<Instant> now = new AtomicReference<>(t0);
    AtomicReference<byte[]> source = new AtomicReference<>(directory(5, t0, "https://hn", root));
    SignedCellKeyDirectory dir =
        new SignedCellKeyDirectory(
            source::get, root.getPublic(), JSON, clock(now), Duration.ofSeconds(60), false);
    assertThat(dir.find("HN"))
        .get()
        .extracting(k -> k.endpoint().toString())
        .isEqualTo("https://hn");
    assertThat(dir.find("HCM")).isEmpty();

    // Bản lùi phiên bản hoặc ký bằng khoá lạ bị bỏ, giữ bản tốt gần nhất.
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair stranger = ec.generateKeyPair();
    source.set(directory(9, t0, "https://gia-mao", stranger));
    now.set(t0.plusSeconds(120));
    assertThat(dir.find("HN"))
        .get()
        .extracting(k -> k.endpoint().toString())
        .isEqualTo("https://hn");
    source.set(directory(4, t0.plusSeconds(120), "https://cu", root));
    now.set(t0.plusSeconds(240));
    assertThat(dir.find("HN"))
        .get()
        .extracting(k -> k.endpoint().toString())
        .isEqualTo("https://hn");
    // Hết hiệu lực của cell thì không trả.
    now.set(t0.plusSeconds(400));
    assertThat(dir.find("HN")).isEmpty();
    // Hết hạn tài liệu mà không tải được bản mới thì không còn cell nào.
    source.set("{}".getBytes(StandardCharsets.UTF_8));
    now.set(t0.plusSeconds(700));
    assertThat(dir.find("HN")).isEmpty();

    SignedCellKeyDirectory plain =
        new SignedCellKeyDirectory(
            () -> {
              try {
                return directory(1, t0, "http://hn", root);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            },
            root.getPublic(),
            JSON,
            clock(new AtomicReference<>(t0)),
            Duration.ofSeconds(60),
            false);
    assertThat(plain.find("HN")).isEmpty();
  }

  @Test
  void codecRejectsMalformedInput() throws Exception {
    JsonTransferCodec codec = new JsonTransferCodec(JSON, 200);
    Part part =
        new Part(
            "a.json",
            "KET_QUA_XEP_LOAI",
            "application/json",
            "{}".getBytes(StandardCharsets.UTF_8));
    List<Part> back = codec.unbundle(codec.bundle(List.of(part)));
    assertThat(back).hasSize(1);
    assertThat(back.get(0).content()).isEqualTo(part.content());
    assertThat(codec.manifestDigests(codec.manifest(List.of(part)))).containsKey("a.json");

    assertThatThrownBy(() -> codec.bundle(List.of(new Part("../x", "K", "t", new byte[0]))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> codec.unbundle(codec.bundle(List.of(new Part("b", "K", "t", new byte[300])))))
        .isInstanceOf(TransferCodec.MalformedException.class);
    assertThatThrownBy(() -> codec.unbundle(zip(Map.of("a.json", new byte[1]))))
        .isInstanceOf(TransferCodec.MalformedException.class);
    assertThatThrownBy(
            () ->
                codec.unbundle(
                    zip(
                        Map.of(
                            "parts.json", "[{\"name\":\"x\"}]".getBytes(StandardCharsets.UTF_8)))))
        .isInstanceOf(TransferCodec.MalformedException.class);
    assertThatThrownBy(() -> codec.unbundle(new byte[] {1, 2, 3}))
        .isInstanceOf(TransferCodec.MalformedException.class);
    assertThatThrownBy(
            () -> codec.manifestDigests("{\"parts\":[{\"name\":\"a\",\"sha256\":\"zz\"}]}"))
        .isInstanceOf(TransferCodec.MalformedException.class);
    assertThatThrownBy(
            () -> codec.parseHeader("{\"packageId\":\"x\"}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(TransferCodec.MalformedException.class);
    assertThatThrownBy(() -> codec.parseHeader(null))
        .isInstanceOf(TransferCodec.MalformedException.class);
    assertThatThrownBy(() -> codec.parseOutcome(new byte[5000]))
        .isInstanceOf(TransferCodec.MalformedException.class);
    assertThatThrownBy(
            () ->
                codec.parseOutcome(
                    ("{\"packageId\":\""
                            + UUID.randomUUID()
                            + "\",\"sourceTenantId\":\""
                            + UUID.randomUUID()
                            + "\",\"cell\":\"hn thường\",\"status\":\"DA_NHAP\"}")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(TransferCodec.MalformedException.class);
    TransferCodec.Outcome o =
        codec.parseOutcome(
            codec.outcome(
                new TransferCodec.Outcome(
                    UUID.randomUUID(), UUID.randomUUID(), "HCM", "TU_CHOI", "BAM_SAI")));
    assertThat(o.reason()).isEqualTo("BAM_SAI");
  }

  @Test
  void cryptoDetectsTamperingAndWeakKeys() throws Exception {
    JcaPackageCrypto crypto = new JcaPackageCrypto(sign.getPrivate(), box.getPrivate());
    UUID id = UUID.randomUUID();
    PackageCrypto.Sealed sealed =
        crypto.seal(id, "nội dung".getBytes(StandardCharsets.UTF_8), box.getPublic());
    assertThat(
            new String(
                crypto.open(id, sealed.ciphertext(), sealed.wrappedKey()), StandardCharsets.UTF_8))
        .isEqualTo("nội dung");
    // Bản mã ghép sang gói khác (dữ liệu liên kết là mã gói) hoặc bị sửa thì không mở được.
    assertThatThrownBy(
            () -> crypto.open(UUID.randomUUID(), sealed.ciphertext(), sealed.wrappedKey()))
        .isInstanceOf(PackageCrypto.InvalidPackageException.class);
    byte[] tampered = sealed.ciphertext().clone();
    tampered[tampered.length - 1] ^= 1;
    assertThatThrownBy(() -> crypto.open(id, tampered, sealed.wrappedKey()))
        .isInstanceOf(PackageCrypto.InvalidPackageException.class);
    assertThatThrownBy(() -> crypto.open(id, new byte[4], sealed.wrappedKey()))
        .isInstanceOf(PackageCrypto.InvalidPackageException.class);
    byte[] sig = crypto.sign(new byte[] {1});
    assertThat(crypto.verify(new byte[] {1}, sig, sign.getPublic())).isTrue();
    assertThat(crypto.verify(new byte[] {2}, sig, sign.getPublic())).isFalse();
    assertThat(crypto.verify(new byte[] {1}, null, sign.getPublic())).isFalse();

    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    String weak =
        Base64.getEncoder().encodeToString(rsa.generateKeyPair().getPublic().getEncoded());
    assertThatThrownBy(() -> PemKeys.publicKey(weak, "RSA"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void esignSignerSendsOnlyHashAndRsaSignaturesVerify() throws Exception {
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(3072);
    KeyPair org = rsa.generateKeyPair();
    AtomicReference<String> seen = new AtomicReference<>();
    AtomicReference<String> tenantHeader = new AtomicReference<>();
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/v1/esign/org-signatures",
        ex -> {
          byte[] req = ex.getRequestBody().readAllBytes();
          seen.set(new String(req, StandardCharsets.UTF_8));
          tenantHeader.set(ex.getRequestHeaders().getFirst("X-Tenant-Id"));
          try {
            byte[] sha = Base64.getDecoder().decode(JSON.readTree(req).path("sha256").asText());
            // Ký như HSM: chữ ký PKCS#1 trên DigestInfo của băm đã nhận.
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(org.getPrivate());
            s.update(digestInfo(sha));
            byte[] out =
                JSON.writeValueAsBytes(
                    Map.of(
                        "signature",
                        Base64.getEncoder().encodeToString(s.sign()),
                        "algorithm",
                        "SHA256withRSA"));
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
          } catch (Exception e) {
            ex.sendResponseHeaders(500, -1);
          }
          ex.close();
        });
    server.start();
    try {
      EsignOrgSigner signer =
          new EsignOrgSigner(
              java.net.http.HttpClient.newHttpClient(),
              JSON,
              "http://127.0.0.1:" + server.getAddress().getPort() + "/",
              null,
              Duration.ofSeconds(5));
      JcaPackageCrypto crypto = new JcaPackageCrypto(signer, box.getPrivate());
      byte[] header = "{\"packageId\":\"bí mật\"}".getBytes(StandardCharsets.UTF_8);
      UUID tenant = UUID.randomUUID();
      byte[] sig =
          vn.viettel.okrkpi.shared.tenant.TenantContext.call(
              vn.viettel.okrkpi.shared.kernel.TenantId.of(tenant), () -> crypto.sign(header));
      assertThat(crypto.verify(header, sig, org.getPublic())).isTrue();
      assertThat(crypto.verify(new byte[] {1}, sig, org.getPublic())).isFalse();
      assertThat(seen.get()).contains("GOI_CHUYEN_LIEN_CELL").doesNotContain("bí mật");
      assertThat(tenantHeader.get()).isEqualTo(tenant.toString());
      server.removeContext("/internal/v1/esign/org-signatures");
      assertThatThrownBy(() -> crypto.sign(header)).isInstanceOf(IllegalStateException.class);
    } finally {
      server.stop(0);
    }
    assertThatThrownBy(
            () ->
                new EsignOrgSigner(
                    java.net.http.HttpClient.newHttpClient(),
                    JSON,
                    " ",
                    null,
                    Duration.ofSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
  }

  static byte[] digestInfo(byte[] sha256) {
    byte[] prefix = java.util.HexFormat.of().parseHex("3031300d060960864801650304020105000420");
    byte[] out = new byte[prefix.length + sha256.length];
    System.arraycopy(prefix, 0, out, 0, prefix.length);
    System.arraycopy(sha256, 0, out, prefix.length, sha256.length);
    return out;
  }

  static byte[] zip(Map<String, byte[]> entries) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> e : entries.entrySet()) {
        z.putNextEntry(new ZipEntry(e.getKey()));
        z.write(e.getValue());
        z.closeEntry();
      }
    }
    return out.toByteArray();
  }
}
