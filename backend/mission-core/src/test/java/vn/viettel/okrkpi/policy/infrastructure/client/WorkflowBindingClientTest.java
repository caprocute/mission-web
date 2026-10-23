package vn.viettel.okrkpi.policy.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/**
 * Client API nội bộ {@code wf_binding} của SVC-06 với máy chủ HTTP giả (CORE-05-11): đường thành
 * công kèm token dịch vụ, tenant, {@code If-Match}, {@code Idempotency-Key}; 422 linter thành
 * {@code POLICY-422-WF-PARAMS} giữ quy tắc; 409 thành xung đột phiên bản; SVC-06 ngừng thành 503.
 */
class WorkflowBindingClientTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID VERSION = UUID.randomUUID();

  private HttpServer server;
  private ResilientClientFactory factory;
  private final Map<String, Integer> status = new ConcurrentHashMap<>();
  private final Map<String, String> bodies = new ConcurrentHashMap<>();
  private final Map<String, String> seen = new ConcurrentHashMap<>();
  private WorkflowBindingClient client;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/v1/workflow/",
        ex -> {
          String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
          seen.put(key + "#query", String.valueOf(ex.getRequestURI().getRawQuery()));
          seen.put(key + "#auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
          seen.put(key + "#tenant", String.valueOf(ex.getRequestHeaders().getFirst("X-Tenant-Id")));
          seen.put(key + "#ifMatch", String.valueOf(ex.getRequestHeaders().getFirst("If-Match")));
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
                    .maxAttempts(2)
                    .waitDuration(Duration.ofMillis(10))
                    .retryExceptions(IOException.class)
                    .build()),
            TimeLimiterRegistry.of(
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).build()),
            BulkheadRegistry.ofDefaults());
    client =
        new WorkflowBindingClient(
            factory.create(
                WorkflowBindingClient.INSTANCE,
                "http://127.0.0.1:" + server.getAddress().getPort()),
            () -> "svc-token");
  }

  @AfterEach
  void stop() throws Exception {
    server.stop(0);
    factory.destroy();
  }

  private static String binding(long rowVersion) {
    return "{\"id\":\"0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f4c01\",\"processKind\":\"DANH_GIA\","
        + "\"templateKey\":\"duyet-ho-so-danh-gia\",\"templateVersionNo\":3,"
        + "\"scope\":{\"flow\":\"DANG\",\"fromPeriodId\":\"0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f4d01\"},"
        + "\"params\":{\"so_cap\":4},\"status\":\"NHAP\",\"rowVersion\":"
        + rowVersion
        + "}";
  }

  @Test
  void docVaLuuMangTokenTenantIfMatchVaKhoaChongGhiTrung() {
    String one = "GET /internal/v1/workflow/bindings/DANH_GIA";
    bodies.put(one, binding(4));
    var found = client.find(TENANT, VERSION, "DANH_GIA").orElseThrow();
    assertThat(found.rowVersion()).isEqualTo(4);
    assertThat(found.body()).containsEntry("templateKey", "duyet-ho-so-danh-gia");
    assertThat(seen.get(one + "#auth")).isEqualTo("Bearer svc-token");
    assertThat(seen.get(one + "#tenant")).isEqualTo(TENANT.value().toString());
    assertThat(seen.get(one + "#query")).isEqualTo("policyPackVersionId=" + VERSION);

    String list = "GET /internal/v1/workflow/bindings";
    bodies.put(list, "{\"items\":[" + binding(0) + "," + binding(1) + "]}");
    assertThat(client.list(TENANT, VERSION)).hasSize(2);

    String put = "PUT /internal/v1/workflow/bindings/DANH_GIA";
    bodies.put(put, binding(5));
    var saved =
        client.save(TENANT, VERSION, "DANH_GIA", 4, "k-1", Map.of("params", Map.of("so_cap", 3)));
    assertThat(saved.rowVersion()).isEqualTo(5);
    assertThat(seen.get(put + "#ifMatch")).isEqualTo("\"4\"");
    assertThat(seen.get(put + "#idem")).isEqualTo("k-1");
    assertThat(seen.get(put + "#body")).contains("\"so_cap\":3");

    String sim = "POST /internal/v1/workflow/bindings/simulate";
    bodies.put(sim, "{\"steps\":[],\"lint\":{\"passed\":true,\"issues\":[]}}");
    assertThat(client.simulate(TENANT, Map.of("templateKey", "x"), "k-2")).containsKey("lint");
    assertThat(seen.get(sim + "#idem")).isEqualTo("k-2");
  }

  @Test
  void danhMucMauChiLayMauHieuLucVaPhienBanKhongCoLaRong() {
    String list = "GET /internal/v1/workflow/templates";
    bodies.put(list, "{\"items\":[],\"nextCursor\":null}");
    assertThat(client.templates(TENANT, "DANH_GIA", "duyệt", 20, "c1")).containsKey("items");
    assertThat(seen.get(list + "#query"))
        .startsWith("status=HIEU_LUC&limit=20&processKind=DANH_GIA&q=")
        .endsWith("&cursor=c1");
    assertThat(client.templates(TENANT, null, null, 50, null)).containsKey("items");
    assertThat(seen.get(list + "#query")).isEqualTo("status=HIEU_LUC&limit=50");

    String version = "GET /internal/v1/workflow/templates/duyet-okr/versions/2";
    bodies.put(version, "{\"key\":\"duyet-okr\",\"versionNo\":2,\"paramSchema\":{}}");
    assertThat(client.template(TENANT, "duyet-okr", 2)).isPresent();
    status.put("GET /internal/v1/workflow/templates/khong-co/versions/1", 404);
    assertThat(client.template(TENANT, "khong-co", 1)).isEmpty();
    status.put("GET /internal/v1/workflow/bindings/KHAC", 404);
    assertThat(client.find(TENANT, VERSION, "KHAC")).isEmpty();
  }

  @Test
  void loiLinterGiuQuyTacViPhamVaMaCuaHopDong() {
    String put = "PUT /internal/v1/workflow/bindings/DANH_GIA";
    status.put(put, 422);
    bodies.put(
        put,
        "{\"code\":\"WORKFLOW-422-LINT\",\"detail\":\"Tham số vi phạm linter\",\"issues\":["
            + "{\"rule\":\"so-cap-trong-mien\",\"elementId\":\"cap\",\"message\":\"Số cấp 5\"},"
            + "{\"rule\":\"khong-bo-buoc-ky\",\"message\":\"Bỏ bước ký\"}]}");
    assertThatThrownBy(() -> client.save(TENANT, VERSION, "DANH_GIA", 0, "k", Map.of()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.errorCode().code()).isEqualTo("POLICY-422-WF-PARAMS");
              assertThat(e.msg()).isEqualTo("MSG-15-09");
              assertThat(e.errors())
                  .extracting(ProblemFieldError::field, ProblemFieldError::rule)
                  .containsExactly(
                      tuple("cap", "so-cap-trong-mien"), tuple("params", "khong-bo-buoc-ky"));
            });

    bodies.put(put, "{\"code\":\"POLICY-422-TEMPLATE-NOT-ACTIVE\",\"detail\":\"Mẫu ngừng\"}");
    assertThatThrownBy(() -> client.save(TENANT, VERSION, "DANH_GIA", 0, "k", Map.of()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo("POLICY-422-TEMPLATE-NOT-ACTIVE"));

    status.put(put, 409);
    bodies.put(put, "{\"code\":\"WORKFLOW-409-VERSION\"}");
    assertThatThrownBy(() -> client.save(TENANT, VERSION, "DANH_GIA", 2, "k", Map.of()))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void svc06LoiHoacNgungThanh503KemRetryAfter() {
    String one = "GET /internal/v1/workflow/bindings/DANH_GIA";
    status.put(one, 503);
    assertThatThrownBy(() -> client.find(TENANT, VERSION, "DANH_GIA"))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            e -> {
              assertThat(e.dependency()).isEqualTo("workflow-service");
              assertThat(e.retryAfterSeconds()).isPositive();
            });
    status.put(one, 401);
    assertThatThrownBy(() -> client.find(TENANT, VERSION, "DANH_GIA"))
        .isInstanceOf(DependencyUnavailableException.class);
    server.stop(0);
    assertThatThrownBy(() -> client.list(TENANT, VERSION))
        .isInstanceOf(DependencyUnavailableException.class);
    assertThatThrownBy(() -> client.save(TENANT, VERSION, "DANH_GIA", 0, "k", Map.of()))
        .isInstanceOf(DependencyUnavailableException.class);
  }
}
