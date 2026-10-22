package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.DigestInfo;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;

/**
 * Máy chủ CSC API v2 giả lập cho DV-10-02 (JDK HttpServer, không thêm thư viện): token OAuth2
 * client credentials, {@code credentials/list}, {@code credentials/info}, {@code signHash} bất đồng
 * bộ, {@code signPolling}, và một TSA RFC 3161. Chứng thư người ký, CA và TSA sinh mới mỗi lần
 * chạy; khoá riêng chỉ nằm trong bộ nhớ kiểm thử. Kịch bản chọn theo {@code userID} (mã người ký).
 */
final class CscMockServer implements AutoCloseable {

  static final String CLIENT_ID = "mission-esign";
  static final String CLIENT_SECRET = "bi-mat-kiem-thu";

  /** Kịch bản của một người ký. */
  enum Scenario {
    /** Người ký xác nhận sau {@link #confirmAfter}. */
    SIGN,
    /** Người ký bấm từ chối trên ứng dụng. */
    REJECT,
    /** Người ký không xác nhận, phiên MySign hết hạn. */
    EXPIRE,
    /** MySign từ chối tham số phiên (lỗi vĩnh viễn). */
    ERROR,
    /** Mọi lời gọi CSC trả 503. */
    FAIL_5XX
  }

  private final HttpServer server;
  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, Scenario> scenarios = new ConcurrentHashMap<>();
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final Map<String, Boolean> tokens = new ConcurrentHashMap<>();
  private final AtomicInteger cscCalls = new AtomicInteger();
  private volatile Duration confirmAfter = Duration.ofSeconds(1);
  private volatile Duration delay = Duration.ZERO;

  final KeyPair caKey;
  final X509Certificate caCert;
  final KeyPair signerKey;
  final X509Certificate signerCert;
  final KeyPair tsaKey;
  final X509Certificate tsaCert;
  private final List<String> chain;

  CscMockServer() throws Exception {
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    caKey = rsa.generateKeyPair();
    signerKey = rsa.generateKeyPair();
    tsaKey = rsa.generateKeyPair();
    Instant now = Instant.now();
    X500Name ca = new X500Name("CN=CA thử nghiệm Mission,O=Mission,C=VN");
    caCert =
        cert(
            ca,
            ca,
            caKey,
            caKey.getPublic(),
            now,
            b -> {
              b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
              b.addExtension(
                  Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            });
    signerCert =
        cert(
            ca,
            new X500Name("CN=Nguyễn Văn Thử,O=Mission,C=VN"),
            caKey,
            signerKey.getPublic(),
            now,
            b ->
                b.addExtension(
                    Extension.keyUsage,
                    true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation)));
    tsaCert =
        cert(
            ca,
            new X500Name("CN=TSA thử nghiệm,O=Mission,C=VN"),
            caKey,
            tsaKey.getPublic(),
            now,
            b ->
                b.addExtension(
                    Extension.extendedKeyUsage,
                    true,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping)));
    chain = List.of(b64(signerCert.getEncoded()), b64(caCert.getEncoded()));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/oauth2/token", this::token);
    server.createContext("/csc/v2/", this::csc);
    server.createContext("/tsa", this::tsa);
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void scenario(UUID signer, Scenario scenario) {
    scenarios.put(signer.toString(), scenario);
  }

  void confirmAfter(Duration value) {
    confirmAfter = value;
  }

  void delay(Duration value) {
    delay = value;
  }

  int cscCalls() {
    return cscCalls.get();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void token(HttpExchange ex) throws IOException {
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, String> form = new ConcurrentHashMap<>();
    for (String pair : body.split("&")) {
      String[] kv = pair.split("=", 2);
      if (kv.length == 2) {
        form.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
      }
    }
    if (!"client_credentials".equals(form.get("grant_type"))
        || !CLIENT_ID.equals(form.get("client_id"))
        || !CLIENT_SECRET.equals(form.get("client_secret"))) {
      send(ex, 401, Map.of("error", "invalid_client"));
      return;
    }
    String token = "t-" + UUID.randomUUID();
    tokens.put(token, true);
    send(ex, 200, Map.of("access_token", token, "token_type", "Bearer", "expires_in", 3600));
  }

  @SuppressWarnings("unchecked")
  private void csc(HttpExchange ex) throws IOException {
    cscCalls.incrementAndGet();
    sleep(delay);
    String auth = ex.getRequestHeaders().getFirst("Authorization");
    if (auth == null || !tokens.containsKey(auth.replace("Bearer ", ""))) {
      send(ex, 401, Map.of("error", "invalid_token"));
      return;
    }
    Map<String, Object> req = json.readValue(ex.getRequestBody(), Map.class);
    String op = ex.getRequestURI().getPath().substring("/csc/v2/".length());
    switch (op) {
      case "credentials/list" -> {
        String user = (String) req.get("userID");
        if (fails(user)) {
          send(ex, 503, Map.of("error", "temporarily_unavailable"));
          return;
        }
        send(ex, 200, Map.of("credentialIDs", List.of("cred-" + user)));
      }
      case "credentials/info" ->
          send(
              ex,
              200,
              Map.of(
                  "key",
                  Map.of("status", "enabled", "algo", List.of("1.2.840.113549.1.1.1"), "len", 2048),
                  "cert",
                  Map.of("status", "valid", "certificates", chain)));
      case "signatures/signHash" -> {
        String user = ((String) req.get("credentialID")).substring("cred-".length());
        if (!"A".equals(req.get("operationMode"))) {
          send(ex, 400, Map.of("error", "invalid_request"));
          return;
        }
        String id = UUID.randomUUID().toString();
        List<byte[]> hashes =
            ((List<String>) req.get("hashes")).stream().map(Base64.getDecoder()::decode).toList();
        sessions.put(id, new Session(user, hashes, Instant.now()));
        send(ex, 200, Map.of("responseID", id));
      }
      case "signatures/signPolling" -> poll(ex, sessions.get((String) req.get("responseID")));
      default -> send(ex, 404, Map.of("error", "invalid_request"));
    }
  }

  private void poll(HttpExchange ex, Session session) throws IOException {
    if (session == null) {
      send(ex, 400, Map.of("error", "invalid_request"));
      return;
    }
    Scenario scenario = scenarios.getOrDefault(session.user(), Scenario.SIGN);
    if (scenario == Scenario.FAIL_5XX) {
      send(ex, 503, Map.of("error", "temporarily_unavailable"));
      return;
    }
    if (Instant.now().isBefore(session.startedAt().plus(confirmAfter))) {
      send(ex, 400, Map.of("error", "authorization_pending"));
      return;
    }
    switch (scenario) {
      case REJECT -> send(ex, 400, Map.of("error", "access_denied"));
      case EXPIRE -> send(ex, 400, Map.of("error", "expired_token"));
      case ERROR -> send(ex, 400, Map.of("error", "invalid_request"));
      default -> {
        List<String> signatures = new ArrayList<>();
        for (byte[] hash : session.hashes()) {
          signatures.add(b64(sign(signerKey.getPrivate(), hash)));
        }
        send(ex, 200, Map.of("signatures", signatures));
      }
    }
  }

  private boolean fails(String user) {
    return scenarios.getOrDefault(user, Scenario.SIGN) == Scenario.FAIL_5XX;
  }

  private void tsa(HttpExchange ex) throws IOException {
    try {
      TimeStampRequest request = new TimeStampRequest(ex.getRequestBody().readAllBytes());
      TimeStampTokenGenerator generator =
          new TimeStampTokenGenerator(
              new JcaSimpleSignerInfoGeneratorBuilder()
                  .build("SHA256withRSA", tsaKey.getPrivate(), tsaCert),
              new JcaDigestCalculatorProviderBuilder()
                  .build()
                  .get(new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256)),
              new ASN1ObjectIdentifier("1.3.6.1.4.1.99999.1"));
      generator.addCertificates(new JcaCertStore(List.of(tsaCert)));
      byte[] reply =
          new TimeStampResponseGenerator(generator, TSPAlgorithms.ALLOWED)
              .generate(request, BigInteger.valueOf(System.nanoTime()), new Date())
              .getEncoded();
      ex.getResponseHeaders().add("Content-Type", "application/timestamp-reply");
      ex.sendResponseHeaders(200, reply.length);
      try (OutputStream out = ex.getResponseBody()) {
        out.write(reply);
      }
    } catch (Exception e) {
      ex.sendResponseHeaders(500, -1);
      ex.close();
    }
  }

  /** Chữ ký RSA PKCS#1 v1.5 trên một băm SHA-256 đã tính sẵn (như HSM của nhà cung cấp). */
  static byte[] sign(PrivateKey key, byte[] sha256) {
    try {
      Signature rsa = Signature.getInstance("NONEwithRSA");
      rsa.initSign(key);
      rsa.update(
          new DigestInfo(
                  new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE),
                  sha256)
              .getEncoded());
      return rsa.sign();
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private void send(HttpExchange ex, int status, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void sleep(Duration d) {
    if (!d.isZero()) {
      try {
        Thread.sleep(d);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static String b64(byte[] b) {
    return Base64.getEncoder().encodeToString(b);
  }

  private interface Extensions {
    void add(JcaX509v3CertificateBuilder builder) throws IOException;
  }

  private static X509Certificate cert(
      X500Name issuer,
      X500Name subject,
      KeyPair issuerKey,
      java.security.PublicKey subjectKey,
      Instant now,
      Extensions extensions)
      throws Exception {
    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(now.toEpochMilli())
                .add(BigInteger.valueOf(subject.hashCode() & 0xffff)),
            Date.from(now.minus(Duration.ofDays(1))),
            Date.from(now.plus(Duration.ofDays(365))),
            subject,
            subjectKey);
    extensions.add(builder);
    return new JcaX509CertificateConverter()
        .getCertificate(
            builder.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey.getPrivate())));
  }

  private record Session(String user, List<byte[]> hashes, Instant startedAt) {}
}
