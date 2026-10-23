package vn.viettel.okrkpi.integ.infrastructure.cloudevents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.viettel.okrkpi.integ.application.outbound.CloudEventsMapper;
import vn.viettel.okrkpi.integ.application.outbound.CloudEventsMapperTest;
import vn.viettel.okrkpi.integ.application.outbound.JwsMode;
import vn.viettel.okrkpi.integ.application.outbound.OutboundPolicy;
import vn.viettel.okrkpi.integ.application.outbound.OutboundRejectedException;
import vn.viettel.okrkpi.integ.application.outbound.SignedMessage;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.shared.kernel.Classification;

/**
 * Gửi CloudEvents đã ký qua mTLS tới máy chủ giả lập bắt buộc chứng thư client (DV-13-02): thiếu
 * chứng thư thì bị từ chối ở bắt tay TLS, có chứng thư thì gửi được và bên nhận kiểm được chữ ký.
 */
class CloudEventsMtlsSenderTest {

  static final String CODE = "CSDL_CBCCVC";
  static final ObjectMapper JSON = new ObjectMapper();

  @TempDir static Path pkiDir;
  static TestPki pki;

  @TempDir Path work;
  HttpsServer server;
  final List<Map.Entry<Map<String, List<String>>, byte[]>> received = new CopyOnWriteArrayList<>();
  final AtomicInteger status = new AtomicInteger(200);
  final AtomicReference<String> ackHeader = new AtomicReference<>();
  final AtomicReference<String> responseBody = new AtomicReference<>("");
  NimbusPayloadSigner signer;
  CloudEventsMapper mapper;
  MtlsHttpClients mtls;

  @BeforeAll
  static void pki() throws Exception {
    pki = TestPki.create(pkiDir);
  }

  @BeforeEach
  void start() throws Exception {
    SSLContext ctx = pki.serverContext();
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(
        new HttpsConfigurator(ctx) {
          @Override
          public void configure(HttpsParameters params) {
            SSLParameters p = ctx.getDefaultSSLParameters();
            p.setNeedClientAuth(true);
            params.setSSLParameters(p);
          }
        });
    server.createContext(
        "/webhooks/v1",
        ex -> {
          received.add(
              Map.entry(Map.copyOf(ex.getRequestHeaders()), ex.getRequestBody().readAllBytes()));
          if (ackHeader.get() != null) {
            ex.getResponseHeaders().add(CloudEventsHttpSender.ACK_HEADER, ackHeader.get());
          }
          byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
          int code = status.get();
          ex.sendResponseHeaders(code, code == 204 || body.length == 0 ? -1 : body.length);
          if (body.length > 0 && code != 204) {
            ex.getResponseBody().write(body);
          }
          ex.close();
        });
    server.start();
    Path jwks = work.resolve("jwks.json");
    Files.writeString(
        jwks,
        new JWKSet(new ECKeyGenerator(Curve.P_256).keyID("cell-hcm-1").generate()).toString(false));
    signer = new NimbusPayloadSigner(new JwkSigningKeys(jwks));
    mapper =
        new CloudEventsMapper(
            "cell-hcm", "https://api.okr.viettel.vn/schemas", JSON, Clock.systemUTC());
    mtls = new MtlsHttpClients(work.resolve("tls"), Duration.ofSeconds(5));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  String endpoint() {
    return "https://localhost:" + server.getAddress().getPort() + "/webhooks/v1";
  }

  Connector connector(String endpoint) {
    return new Connector(
        UUID.randomUUID(),
        UUID.randomUUID(),
        CODE,
        Protocol.REST,
        endpoint,
        "cbccvc/token",
        Direction.RA,
        null,
        true);
  }

  CloudEventsHttpSender sender(OutboundPolicy policy) {
    return new CloudEventsHttpSender(
        policy,
        mapper,
        signer,
        mtls,
        HttpClient.newHttpClient(),
        path -> Optional.of("tok-gia-lap"),
        JSON,
        Duration.ofSeconds(5));
  }

  @Test
  void clientWithoutCertificateIsRejectedByServer() throws Exception {
    HttpClient noCert =
        HttpClient.newBuilder()
            .sslContext(pki.trustOnly())
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    assertThatThrownBy(
            () ->
                noCert.send(
                    HttpRequest.newBuilder(URI.create(endpoint()))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                    HttpResponse.BodyHandlers.discarding()))
        .isInstanceOf(IOException.class);
    assertThat(received).isEmpty();

    // Bộ gửi bắt buộc mTLS mà chưa có chứng thư của kết nối thì không lùi về kết nối thường.
    byte[] ce = CloudEventSigningTest.evt12();
    CloudEventsHttpSender sender = sender(OutboundPolicy.defaults(CODE));
    assertThatThrownBy(() -> sender.send(connector(endpoint()), UUID.randomUUID(), "XEP_LOAI", ce))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(
            () -> sender.send(connector("http://localhost:1/x"), UUID.randomUUID(), "XEP_LOAI", ce))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("https");
    assertThat(received).isEmpty();
  }

  @Test
  void clientWithCertificateSendsSignedEventThatReceiverVerifies() throws Exception {
    pki.writeClientPem(mtls.dir(CODE));
    byte[] ce = CloudEventSigningTest.evt12();
    UUID messageId = UUID.randomUUID();
    ackHeader.set("CBCCVC-2027-000123");
    CloudEventsHttpSender sender = sender(OutboundPolicy.defaults(CODE));
    assertThat(sender.connectorCode()).isEqualTo(CODE);
    assertThat(sender.send(connector(endpoint()), messageId, "XEP_LOAI", ce))
        .contains("CBCCVC-2027-000123");

    Map.Entry<Map<String, List<String>>, byte[]> req = received.get(0);
    Map<String, List<String>> h = req.getKey();
    assertThat(h.get("Content-type")).containsExactly(SignedMessage.CLOUDEVENTS_JSON);
    assertThat(h.get("Idempotency-key")).containsExactly(messageId.toString());
    assertThat(h.get("Authorization")).containsExactly("Bearer tok-gia-lap");
    String signature = h.get("X-jws-signature").get(0);
    CloudEventVerifier verifier = new CloudEventVerifier(signer.publicJwks(), Map.of());
    assertThat(verifier.verifyDetached(req.getValue(), signature).path("id").asText())
        .isEqualTo(CloudEventsMapperTest.EVENT.toString());

    // Máy khách được giữ lại giữa các lần gửi; 202 là chờ xác nhận sau.
    HttpClient first = mtls.client(CODE);
    assertThat(mtls.client(CODE)).isSameAs(first);
    status.set(202);
    assertThat(sender.send(connector(endpoint()), UUID.randomUUID(), "XEP_LOAI", ce)).isEmpty();
  }

  @Test
  void compactModeAndAcknowledgementVariants() throws Exception {
    pki.writeClientPem(mtls.dir(CODE));
    byte[] ce = CloudEventSigningTest.evt12();
    CloudEventsHttpSender sender =
        sender(
            new OutboundPolicy(
                CODE, Set.of(Classification.C0, Classification.C1), JwsMode.COMPACT, true));
    responseBody.set("{\"ackRef\":\"" + "A".repeat(200) + "\"}");
    status.set(201);
    assertThat(sender.send(connector(endpoint()), UUID.randomUUID(), "XEP_LOAI", ce).orElseThrow())
        .hasSize(CloudEventsHttpSender.MAX_ACK);
    Map.Entry<Map<String, List<String>>, byte[]> req = received.get(0);
    assertThat(req.getKey().get("Content-type")).containsExactly(SignedMessage.JOSE);
    assertThat(req.getKey()).doesNotContainKey("X-jws-signature");
    new CloudEventVerifier(signer.publicJwks(), Map.of())
        .verifyCompact(new String(req.getValue(), StandardCharsets.US_ASCII));

    responseBody.set("không phải json");
    status.set(200);
    assertThat(sender.send(connector(endpoint()), UUID.randomUUID(), "XEP_LOAI", ce))
        .contains("HTTP 200");
    responseBody.set("");
    status.set(204);
    assertThat(sender.send(connector(endpoint()), UUID.randomUUID(), "XEP_LOAI", ce))
        .contains("HTTP 204");
    status.set(503);
    responseBody.set("{\"ackRef\":\"bi-mat\"}");
    assertThatThrownBy(() -> sender.send(connector(endpoint()), UUID.randomUUID(), "XEP_LOAI", ce))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("503")
        .hasMessageNotContaining("bi-mat");
  }

  @Test
  void payloadBreakingPolicyIsNotSentAndPlainModeWorksForMocks() throws Exception {
    byte[] ce = CloudEventSigningTest.evt12();
    CloudEventsHttpSender publicOnly =
        sender(new OutboundPolicy(CODE, Set.of(Classification.C0), JwsMode.DETACHED, false));
    assertThatThrownBy(() -> publicOnly.send(connector(endpoint()), UUID.randomUUID(), "X", ce))
        .isInstanceOf(OutboundRejectedException.class);
    assertThat(received).isEmpty();
    assertThatThrownBy(() -> mtls.dir("../ngoai")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MtlsHttpClients(null, Duration.ofSeconds(1)).dir(CODE))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void pemKeyMustBePkcs8() throws Exception {
    Path tls = mtls.dir(CODE);
    pki.writeClientPem(tls);
    Files.writeString(
        tls.resolve(PemTls.KEY),
        "-----BEGIN EC PRIVATE KEY-----\nAA==\n-----END EC PRIVATE KEY-----\n");
    assertThatThrownBy(() -> mtls.client(CODE))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("PKCS#8");
    pki.writeClientPem(tls);
    Files.writeString(tls.resolve(PemTls.CA), "");
    assertThatThrownBy(() -> mtls.client(CODE)).isInstanceOf(IOException.class);
  }
}
