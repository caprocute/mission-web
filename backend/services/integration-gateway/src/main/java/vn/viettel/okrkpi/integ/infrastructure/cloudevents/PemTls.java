package vn.viettel.okrkpi.integ.infrastructure.cloudevents;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Dựng {@link SSLContext} mTLS từ ba tệp PEM theo bố cục Secret của cert-manager (DV-13-02): {@code
 * tls.crt} (chứng thư tổ chức kèm chuỗi trung gian), {@code tls.key} (khoá riêng PKCS#8, đặt {@code
 * privateKey.encoding: PKCS8} ở Certificate), {@code ca.crt} (CA tin cậy của bên nhận). Khoá không
 * bao giờ nằm trong cấu hình; thư mục do CSI hoặc OpenBao Agent gắn vào pod.
 */
final class PemTls {

  static final String CERT = "tls.crt";
  static final String KEY = "tls.key";
  static final String CA = "ca.crt";

  private static final Pattern PKCS8 =
      Pattern.compile("-----BEGIN PRIVATE KEY-----([A-Za-z0-9+/=\\s]+)-----END PRIVATE KEY-----");

  private PemTls() {}

  static SSLContext context(Path dir) throws IOException {
    try {
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      List<Certificate> chain = certificates(cf, dir.resolve(CERT));
      if (chain.isEmpty()) {
        throw new IOException("Không có chứng thư client trong " + CERT);
      }
      char[] none = new char[0];
      KeyStore keys = KeyStore.getInstance("PKCS12");
      keys.load(null, null);
      keys.setKeyEntry(
          "client", privateKey(dir.resolve(KEY)), none, chain.toArray(Certificate[]::new));
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keys, none);

      KeyStore trust = KeyStore.getInstance("PKCS12");
      trust.load(null, null);
      int i = 0;
      for (Certificate ca : certificates(cf, dir.resolve(CA))) {
        trust.setCertificateEntry("ca-" + i++, ca);
      }
      if (i == 0) {
        throw new IOException("Không có CA tin cậy trong " + CA);
      }
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(trust);

      SSLContext ctx = SSLContext.getInstance("TLSv1.3");
      ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
      return ctx;
    } catch (GeneralSecurityException e) {
      throw new IOException(
          "Chứng thư hoặc khoá mTLS không hợp lệ: " + e.getClass().getSimpleName());
    }
  }

  private static List<Certificate> certificates(CertificateFactory cf, Path file)
      throws IOException, GeneralSecurityException {
    try (ByteArrayInputStream in = new ByteArrayInputStream(Files.readAllBytes(file))) {
      return new ArrayList<>(cf.generateCertificates(in));
    }
  }

  static PrivateKey privateKey(Path file) throws IOException, GeneralSecurityException {
    Matcher m = PKCS8.matcher(Files.readString(file, StandardCharsets.US_ASCII));
    if (!m.find()) {
      throw new IOException("Khoá mTLS phải ở dạng PEM PKCS#8 (BEGIN PRIVATE KEY)");
    }
    PKCS8EncodedKeySpec spec =
        new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(m.group(1).trim()));
    GeneralSecurityException last = null;
    for (String alg : List.of("EC", "RSA", "Ed25519")) {
      try {
        return KeyFactory.getInstance(alg).generatePrivate(spec);
      } catch (GeneralSecurityException e) {
        last = e;
      }
    }
    throw last;
  }
}
