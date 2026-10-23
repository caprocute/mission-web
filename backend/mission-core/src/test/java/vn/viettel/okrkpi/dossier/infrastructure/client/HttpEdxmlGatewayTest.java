package vn.viettel.okrkpi.dossier.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.EdxmlGateway;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/**
 * Cổng SVC-13 với máy chủ HTTP giả theo hợp đồng DV-13-05 (wiki trang 14): thân gửi, header, ánh xạ
 * 422 {@code reason}, 413, trạng thái và mã lỗi thư chết thành lý do tiếng Việt.
 */
class HttpEdxmlGatewayTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());

  private HttpServer server;
  private ResilientClientFactory factory;
  private final Map<String, Integer> status = new ConcurrentHashMap<>();
  private final Map<String, String> bodies = new ConcurrentHashMap<>();
  private final Map<String, String> seen = new ConcurrentHashMap<>();
  private HttpEdxmlGateway gateway;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/v1/integ/",
        ex -> {
          String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
          seen.put(key + "#auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
          seen.put(key + "#tenant", String.valueOf(ex.getRequestHeaders().getFirst("X-Tenant-Id")));
          seen.put(
              key + "#idem", String.valueOf(ex.getRequestHeaders().getFirst("Idempotency-Key")));
          seen.put(
              key + "#body",
              new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] b = bodies.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
          int code = status.getOrDefault(key, 200);
          ex.getResponseHeaders()
              .add("Content-Type", code >= 400 ? "application/problem+json" : "application/json");
          ex.sendResponseHeaders(code, b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    server.start();
    factory =
        new ResilientClientFactory(
            new StaticListableBeanFactory().getBeanProvider(RestClient.Builder.class),
            CircuitBreakerRegistry.ofDefaults(),
            RetryRegistry.of(
                RetryConfig.custom()
                    .maxAttempts(1)
                    .waitDuration(Duration.ofMillis(10))
                    .retryExceptions(IOException.class)
                    .build()),
            TimeLimiterRegistry.of(
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).build()),
            BulkheadRegistry.ofDefaults());
    gateway =
        new HttpEdxmlGateway(
            factory.create(
                HttpEdxmlGateway.INSTANCE, "http://127.0.0.1:" + server.getAddress().getPort()),
            () -> "svc-token",
            new ObjectMapper());
  }

  @AfterEach
  void stop() throws Exception {
    server.stop(0);
    factory.destroy();
  }

  private static EdxmlGateway.Outgoing outgoing(UUID id) {
    return new EdxmlGateway.Outgoing(
        id,
        UUID.randomUUID(),
        "BTC_TW",
        "Hồ sơ diện TW",
        "DP-2026-N-1",
        List.of(new EdxmlGateway.Attachment("a.pdf", "application/pdf", new byte[] {1, 2, 3})));
  }

  @Test
  void guiKemTokenTenantKhoaVaBase64() throws Exception {
    UUID id = UUID.randomUUID();
    String post = "POST /internal/v1/integ/edxml-transfers";
    bodies.put(post, "{\"transferId\":\"" + id + "\",\"status\":\"CHO\"}");
    status.put(post, 202);
    EdxmlGateway.RemoteState s = gateway.send(TENANT, outgoing(id));
    assertThat(s.status()).isEqualTo("CHO");
    assertThat(s.attempts()).isZero();
    assertThat(seen.get(post + "#auth")).isEqualTo("Bearer svc-token");
    assertThat(seen.get(post + "#tenant")).isEqualTo(TENANT.value().toString());
    assertThat(seen.get(post + "#idem")).isEqualTo(id.toString());
    JsonNode body = new ObjectMapper().readTree(seen.get(post + "#body"));
    assertThat(body.path("destination").asText()).isEqualTo("BTC_TW");
    assertThat(body.path("documentNumber").asText()).isEqualTo("DP-2026-N-1");
    assertThat(body.path("attachments").get(0).path("contentBase64").asText())
        .isEqualTo(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
  }

  @Test
  void anhXaLoiTuChoi() {
    String post = "POST /internal/v1/integ/edxml-transfers";
    status.put(post, 422);
    bodies.put(post, "{\"reason\":\"DESTINATION_NOT_CONFIGURED\"}");
    assertThatThrownBy(() -> gateway.send(TENANT, outgoing(UUID.randomUUID())))
        .isInstanceOf(EdxmlGateway.DestinationNotConfiguredException.class);
    bodies.put(post, "{\"reason\":\"PAYLOAD_TOO_LARGE\"}");
    assertThatThrownBy(() -> gateway.send(TENANT, outgoing(UUID.randomUUID())))
        .isInstanceOf(EdxmlGateway.PayloadTooLargeException.class);
    status.put(post, 413);
    assertThatThrownBy(() -> gateway.send(TENANT, outgoing(UUID.randomUUID())))
        .isInstanceOf(EdxmlGateway.PayloadTooLargeException.class);
    status.put(post, 400);
    assertThatThrownBy(() -> gateway.send(TENANT, outgoing(UUID.randomUUID())))
        .isInstanceOf(EdxmlGateway.UnavailableException.class);
    status.put(post, 202);
    bodies.put(post, "");
    assertThat(gateway.send(TENANT, outgoing(UUID.randomUUID())).status()).isEqualTo("CHO");
  }

  @Test
  void docTrangThaiVaLyDo() {
    UUID id = UUID.randomUUID();
    String get = "GET /internal/v1/integ/edxml-transfers/" + id;
    bodies.put(
        get,
        "{\"transferId\":\""
            + id
            + "\",\"status\":\"DA_XAC_NHAN\",\"attempts\":2,\"ackRef\":\"BN-9\","
            + "\"acknowledgedAt\":\"2027-01-15T03:00:00Z\",\"nextAttemptAt\":null}");
    EdxmlGateway.RemoteState s = gateway.state(TENANT, id).orElseThrow();
    assertThat(s.status()).isEqualTo("DA_XAC_NHAN");
    assertThat(s.ackRef()).isEqualTo("BN-9");
    assertThat(s.acknowledgedAt()).isEqualTo(Instant.parse("2027-01-15T03:00:00Z"));

    bodies.put(
        get,
        "{\"status\":\"LOI\",\"attempts\":5,\"lastError\":\"CertificateExpiredException\","
            + "\"nextAttemptAt\":\"2027-01-15T10:30:00+07:00\"}");
    s = gateway.state(TENANT, id).orElseThrow();
    assertThat(s.lastError()).isEqualTo(HttpEdxmlGateway.REASON_CERTIFICATE);
    assertThat(s.nextAttemptAt()).isEqualTo(Instant.parse("2027-01-15T03:30:00Z"));

    status.put(get, 404);
    assertThat(gateway.state(TENANT, id)).isEmpty();
    status.put(get, 200);
    bodies.put(get, "{}");
    assertThat(gateway.state(TENANT, id)).isEmpty();
  }

  @Test
  void maLoiThuChetThanhLyDo() {
    assertThat(HttpEdxmlGateway.reasonText(null)).isNull();
    assertThat(HttpEdxmlGateway.reasonText("SocketTimeoutException"))
        .isEqualTo(HttpEdxmlGateway.REASON_TIMEOUT);
    assertThat(HttpEdxmlGateway.reasonText("IllegalStateException"))
        .isEqualTo(HttpEdxmlGateway.REASON_TIMEOUT);
    assertThat(HttpEdxmlGateway.reasonText("SSLHandshakeException"))
        .isEqualTo(HttpEdxmlGateway.REASON_CERTIFICATE);
    assertThat(HttpEdxmlGateway.reasonText("KHONG_RO")).isEqualTo(HttpEdxmlGateway.REASON_OTHER);
  }
}
