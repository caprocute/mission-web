package vn.viettel.okrkpi.esign.infrastructure.tsa;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;

/**
 * Máy chủ dấu thời gian RFC 3161 giả lập trong tiến trình (YC-PT-13): gốc CA tự ký, chứng thư TSA
 * do gốc cấp với EKU {@code timeStamping} critical. {@link #failNext} làm lời gọi kế tiếp trả 503.
 */
public final class TsaMockServer implements AutoCloseable {

  public final X509Certificate root;
  public final X509Certificate tsaCert;
  private final HttpServer server;
  private final TimeStampResponseGenerator responses;
  private final AtomicLong serial = new AtomicLong(1);
  public final AtomicInteger calls = new AtomicInteger();
  public final AtomicInteger failNext = new AtomicInteger();

  public TsaMockServer() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair rootKeys = g.generateKeyPair();
    KeyPair tsaKeys = g.generateKeyPair();
    Instant now = Instant.now();
    X500Name rootName = new X500Name("CN=Mission TSA thu nghiem goc,O=Mission dev");
    JcaX509CertificateConverter conv = new JcaX509CertificateConverter();
    JcaX509v3CertificateBuilder rb =
        new JcaX509v3CertificateBuilder(
            rootName,
            BigInteger.ONE,
            Date.from(now.minus(Duration.ofDays(1))),
            Date.from(now.plus(Duration.ofDays(365))),
            rootName,
            rootKeys.getPublic());
    rb.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
    rb.addExtension(
        Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
    root =
        conv.getCertificate(
            rb.build(new JcaContentSignerBuilder("SHA256withECDSA").build(rootKeys.getPrivate())));
    JcaX509v3CertificateBuilder tb =
        new JcaX509v3CertificateBuilder(
            root,
            BigInteger.TWO,
            Date.from(now.minus(Duration.ofDays(1))),
            Date.from(now.plus(Duration.ofDays(365))),
            new X500Name("CN=Mission TSA thu nghiem,O=Mission dev"),
            tsaKeys.getPublic());
    tb.addExtension(
        Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
    tb.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
    tsaCert =
        conv.getCertificate(
            tb.build(new JcaContentSignerBuilder("SHA256withECDSA").build(rootKeys.getPrivate())));
    TimeStampTokenGenerator tokens =
        new TimeStampTokenGenerator(
            new JcaSimpleSignerInfoGeneratorBuilder()
                .build("SHA256withECDSA", tsaKeys.getPrivate(), tsaCert),
            new org.bouncycastle.operator.bc.BcDigestCalculatorProvider()
                .get(
                    new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                        org.bouncycastle.asn1.oiw.OIWObjectIdentifiers.idSHA1)),
            new ASN1ObjectIdentifier("1.3.6.1.4.1.99999.1"));
    tokens.addCertificates(new JcaCertStore(List.of(tsaCert, root)));
    responses = new TimeStampResponseGenerator(tokens, TSPAlgorithms.ALLOWED);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          calls.incrementAndGet();
          try (ex) {
            if (failNext.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
              ex.sendResponseHeaders(503, -1);
              return;
            }
            TimeStampRequest req = new TimeStampRequest(ex.getRequestBody().readAllBytes());
            byte[] body =
                responses
                    .generate(req, BigInteger.valueOf(serial.getAndIncrement()), new Date())
                    .getEncoded();
            ex.getResponseHeaders().add("Content-Type", "application/timestamp-reply");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
          } catch (Exception e) {
            throw new IOException(e);
          }
        });
    server.start();
  }

  /** Client gọi máy chủ này và tin gốc của nó. */
  public TsaClient client() {
    return new TsaClient(
        List.of(new TsaClient.Endpoint(url(), TsaClientTest.factory().create("tsa-test", url()))),
        List.of(root));
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
