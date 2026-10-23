package vn.viettel.okrkpi.product.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.product.application.evidence.port.IssuedDocuments.Page;
import vn.viettel.okrkpi.product.application.evidence.port.IssuedDocuments.Transferred;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/**
 * Cổng nội bộ văn bản đã ban hành của SVC-13 với máy chủ HTTP giả (CORE-03-04): tìm mang token dịch
 * vụ, tenant, đơn vị; chuyển tệp gốc mang khoá chống ghi trùng; 404 là không có văn bản; lỗi khác
 * và thân sai là 503.
 */
class HttpIssuedDocumentsTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID UNIT = UUID.randomUUID();
  static final UUID EVIDENCE = UUID.randomUUID();
  static final String SEARCH = "GET /internal/v1/integ/issued-docs";
  static final String TRANSFER = "POST /internal/v1/integ/issued-docs/transfer";
  static final String DOC =
      "{\"ref\":\"47-BC/BTCĐUK\",\"docNumber\":\"47-BC/BTCĐUK\",\"docDate\":\"2027-01-19\","
          + "\"summary\":\"Báo cáo chuyên đề\",\"signerName\":\"Trần Thị Bình\","
          + "\"signatureValid\":true}";

  private HttpServer server;
  private ResilientClientFactory factory;
  private final Map<String, Integer> status = new ConcurrentHashMap<>();
  private final Map<String, String> bodies = new ConcurrentHashMap<>();
  private final Map<String, String> seen = new ConcurrentHashMap<>();
  private HttpIssuedDocuments client;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/v1/integ/",
        ex -> {
          String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
          seen.put(key + "#query", String.valueOf(ex.getRequestURI().getQuery()));
          seen.put(key + "#auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
          seen.put(key + "#tenant", String.valueOf(ex.getRequestHeaders().getFirst("X-Tenant-Id")));
          seen.put(
              key + "#idem", String.valueOf(ex.getRequestHeaders().getFirst("Idempotency-Key")));
          seen.put(
              key + "#body",
              new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] b = bodies.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
          int code = status.getOrDefault(key, 200);
          ex.getResponseHeaders().add("Content-Type", "application/json");
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
                    .maxAttempts(2)
                    .waitDuration(Duration.ofMillis(10))
                    .retryExceptions(IOException.class)
                    .build()),
            TimeLimiterRegistry.of(
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).build()),
            BulkheadRegistry.ofDefaults());
    client =
        new HttpIssuedDocuments(
            factory.create(
                HttpIssuedDocuments.INSTANCE, "http://127.0.0.1:" + server.getAddress().getPort()),
            () -> "svc-token");
  }

  @AfterEach
  void stop() throws Exception {
    server.stop(0);
    factory.destroy();
  }

  @Test
  void timVanBanMangTokenTenantVaDonVi() {
    bodies.put(SEARCH, "{\"items\":[" + DOC + "],\"nextCursor\":\"k2\"}");
    Page page = client.search(TENANT, UNIT, "kiểm tra", 20, "k1");
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).docDate()).isEqualTo(LocalDate.of(2027, 1, 19));
    assertThat(page.items().get(0).signatureValid()).isTrue();
    assertThat(page.nextCursor()).isEqualTo("k2");
    assertThat(seen.get(SEARCH + "#auth")).isEqualTo("Bearer svc-token");
    assertThat(seen.get(SEARCH + "#tenant")).isEqualTo(TENANT.value().toString());
    assertThat(seen.get(SEARCH + "#query"))
        .contains("tenantId=" + TENANT.value())
        .contains("orgUnitId=" + UNIT)
        .contains("limit=20")
        .contains("connector=VOFFICE")
        .contains("q=kiểm tra")
        .contains("cursor=k1");

    bodies.put(SEARCH, "{\"items\":[],\"nextCursor\":null}");
    assertThat(client.search(TENANT, UNIT, null, 50, null).items()).isEmpty();
    assertThat(seen.get(SEARCH + "#query")).doesNotContain("q=").doesNotContain("cursor=");

    status.put(SEARCH, 404);
    assertThat(client.search(TENANT, UNIT, null, 50, null).items()).isEmpty();
  }

  @Test
  void chuyenTepGocMangKhoaChongGhiTrung() {
    UUID object = UUID.randomUUID();
    UUID person = UUID.randomUUID();
    bodies.put(
        TRANSFER,
        "{\"objectId\":\""
            + object
            + "\",\"objectVersion\":1,\"sha256\":\""
            + "ab".repeat(32)
            + "\",\"sizeBytes\":4096,\"mediaType\":\"application/pdf\",\"signatureValid\":true,"
            + "\"doc\":"
            + DOC
            + "}");
    Transferred t =
        client.transfer(TENANT, UNIT, "47-BC/BTCĐUK", EVIDENCE, person, "k-1").orElseThrow();
    assertThat(t.objectId()).isEqualTo(object);
    assertThat(t.sha256()).isEqualTo(HexFormat.of().parseHex("ab".repeat(32)));
    assertThat(t.signatureValid()).isTrue();
    assertThat(t.doc().signerName()).isEqualTo("Trần Thị Bình");
    assertThat(seen.get(TRANSFER + "#idem")).isEqualTo("k-1");
    assertThat(seen.get(TRANSFER + "#body"))
        .contains("\"ref\":\"47-BC/BTCĐUK\"")
        .contains("\"requestedBy\":\"" + person + "\"")
        .contains("\"orgUnitId\":\"" + UNIT + "\"")
        .contains("\"ownerRefId\":\"" + EVIDENCE + "\"");

    bodies.put(
        TRANSFER,
        "{\"objectId\":null,\"signatureValid\":false,\"doc\":"
            + DOC.replace("true", "false")
            + "}");
    Transferred invalid =
        client.transfer(TENANT, UNIT, "47-BC", EVIDENCE, person, "k-3").orElseThrow();
    assertThat(invalid.signatureValid()).isFalse();
    assertThat(invalid.objectId()).isNull();
    assertThat(invalid.doc().ref()).isEqualTo("47-BC/BTCĐUK");

    status.put(TRANSFER, 404);
    assertThat(client.transfer(TENANT, UNIT, "khong-co", EVIDENCE, person, "k-2")).isEmpty();
  }

  @Test
  void svc13LoiHoacThanSaiLa503() {
    status.put(TRANSFER, 500);
    assertThatThrownBy(() -> client.transfer(TENANT, UNIT, "x", EVIDENCE, UUID.randomUUID(), "k"))
        .isInstanceOf(DependencyUnavailableException.class)
        .satisfies(
            e ->
                assertThat(((DependencyUnavailableException) e).retryAfter())
                    .isEqualTo(Duration.ofSeconds(5)));
    status.put(SEARCH, 403);
    assertThatThrownBy(() -> client.search(TENANT, UNIT, null, 10, null))
        .isInstanceOf(DependencyUnavailableException.class);
    status.remove(TRANSFER);
    bodies.put(TRANSFER, "{\"objectId\":\"khong-phai-uuid\",\"signatureValid\":true}");
    assertThatThrownBy(() -> client.transfer(TENANT, UNIT, "x", EVIDENCE, UUID.randomUUID(), "k"))
        .isInstanceOf(DependencyUnavailableException.class);
  }
}
