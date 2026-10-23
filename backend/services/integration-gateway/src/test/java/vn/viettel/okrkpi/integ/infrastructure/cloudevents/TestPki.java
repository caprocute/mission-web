package vn.viettel.okrkpi.integ.infrastructure.cloudevents;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Hạ tầng khoá công khai giả lập cho kiểm thử mTLS (DV-13-02): một CA, chứng thư máy chủ cho {@code
 * localhost} và chứng thư client tổ chức, sinh bằng {@code keytool} của JDK (không thêm thư viện).
 * Ghi thư mục client theo bố cục Secret của cert-manager ({@code tls.crt}, {@code tls.key} PKCS#8,
 * {@code ca.crt}).
 */
final class TestPki {

  static final char[] PASS = "changeit".toCharArray();

  final Path dir;
  final Path ca;
  final Path server;
  final Path client;

  private TestPki(Path dir) {
    this.dir = dir;
    this.ca = dir.resolve("ca.p12");
    this.server = dir.resolve("server.p12");
    this.client = dir.resolve("client.p12");
  }

  static TestPki create(Path dir) throws Exception {
    TestPki pki = new TestPki(dir);
    pki.keytool(
        "-genkeypair",
        "-alias",
        "ca",
        "-keyalg",
        "EC",
        "-groupname",
        "secp256r1",
        "-dname",
        "CN=Mission Test CA",
        "-ext",
        "bc:c",
        "-validity",
        "2",
        "-keystore",
        pki.ca.toString());
    pki.keytool(
        "-exportcert",
        "-rfc",
        "-alias",
        "ca",
        "-keystore",
        pki.ca.toString(),
        "-file",
        dir.resolve("ca.pem").toString());
    pki.issue(
        pki.server, "server", "CN=localhost", "SAN=dns:localhost,ip:127.0.0.1", "EKU=serverAuth");
    pki.issue(pki.client, "client", "CN=Don vi gia lap,O=Mission", null, "EKU=clientAuth");
    return pki;
  }

  private void issue(Path store, String alias, String dname, String san, String eku)
      throws Exception {
    keytool(
        "-genkeypair",
        "-alias",
        alias,
        "-keyalg",
        "EC",
        "-groupname",
        "secp256r1",
        "-dname",
        dname,
        "-validity",
        "2",
        "-keystore",
        store.toString());
    Path csr = dir.resolve(alias + ".csr");
    Path crt = dir.resolve(alias + ".pem");
    keytool("-certreq", "-alias", alias, "-keystore", store.toString(), "-file", csr.toString());
    List<String> gencert =
        new ArrayList<>(
            List.of(
                "-gencert",
                "-alias",
                "ca",
                "-keystore",
                ca.toString(),
                "-infile",
                csr.toString(),
                "-outfile",
                crt.toString(),
                "-rfc",
                "-validity",
                "2",
                "-ext",
                eku));
    if (san != null) {
      gencert.addAll(List.of("-ext", san));
    }
    keytool(gencert.toArray(String[]::new));
    keytool(
        "-importcert",
        "-noprompt",
        "-alias",
        "ca",
        "-keystore",
        store.toString(),
        "-file",
        dir.resolve("ca.pem").toString());
    keytool("-importcert", "-alias", alias, "-keystore", store.toString(), "-file", crt.toString());
  }

  private void keytool(String... args) throws Exception {
    List<String> cmd = new ArrayList<>();
    cmd.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
    cmd.addAll(List.of(args));
    cmd.addAll(List.of("-storetype", "PKCS12", "-storepass", new String(PASS)));
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    byte[] out = p.getInputStream().readAllBytes();
    if (p.waitFor() != 0) {
      throw new IllegalStateException(
          "keytool lỗi: "
              + String.join(" ", args)
              + "\n"
              + new String(out, StandardCharsets.UTF_8));
    }
  }

  static KeyStore load(Path file) throws Exception {
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(file)) {
      ks.load(in, PASS);
    }
    return ks;
  }

  /** Ngữ cảnh TLS phía máy chủ: chứng thư {@code localhost}, tin CA giả lập cho client. */
  SSLContext serverContext() throws Exception {
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    KeyStore ks = load(server);
    ks.deleteEntry("ca");
    kmf.init(ks, PASS);
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(kmf.getKeyManagers(), trust().getTrustManagers(), null);
    return ctx;
  }

  /** Ngữ cảnh chỉ tin CA, không có chứng thư client. */
  SSLContext trustOnly() throws Exception {
    SSLContext ctx = SSLContext.getInstance("TLSv1.3");
    ctx.init(null, trust().getTrustManagers(), null);
    return ctx;
  }

  private TrustManagerFactory trust() throws Exception {
    KeyStore t = KeyStore.getInstance("PKCS12");
    t.load(null, null);
    t.setCertificateEntry("ca", load(ca).getCertificate("ca"));
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(t);
    return tmf;
  }

  /** Ghi thư mục chứng thư client theo bố cục cert-manager. */
  void writeClientPem(Path target) throws Exception {
    Files.createDirectories(target);
    KeyStore ks = load(client);
    PrivateKey key = (PrivateKey) ks.getKey("client", PASS);
    StringBuilder chain = new StringBuilder();
    for (Certificate c : ks.getCertificateChain("client")) {
      chain.append(pem("CERTIFICATE", c.getEncoded()));
    }
    Files.writeString(target.resolve(PemTls.CERT), chain);
    Files.writeString(target.resolve(PemTls.KEY), pem("PRIVATE KEY", key.getEncoded()));
    Files.writeString(
        target.resolve(PemTls.CA), pem("CERTIFICATE", load(ca).getCertificate("ca").getEncoded()));
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

  static void deleteQuietly(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException e) {
      // bỏ qua
    }
  }
}
