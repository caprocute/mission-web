package vn.viettel.okrkpi.okr.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.okr.application.port.KrSourceValuePort;
import vn.viettel.okrkpi.okr.application.port.KrSourceValuePort.SourceValue;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/**
 * CORE-01-10: cổng số liệu nguồn với máy chủ HTTP giả thay integration-gateway: thành công mang
 * token dịch vụ và tenant; nguồn chậm 5 giây bị cắt ở 3 giây; ngắt mạch mở sau chuỗi lỗi và đóng
 * lại khi nguồn phục hồi; chỉ số theo nguồn.
 */
class HttpKrSourceValuesTest {

  static final TenantId T = TenantId.of(UUID.randomUUID());
  static final UUID KR = UUID.randomUUID();
  static final String CSDL = "CSDL đảng viên";

  private HttpServer server;
  private ResilientClientFactory factory;
  private ResilientClient http;
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final AtomicReference<String> mode = new AtomicReference<>("ok");
  private final AtomicInteger hits = new AtomicInteger();
  private final AtomicReference<String> seen = new AtomicReference<>();
  private HttpKrSourceValues client;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/internal/v1/integ/kr-source-values",
        ex -> {
          hits.incrementAndGet();
          seen.set(
              ex.getRequestURI().getRawQuery()
                  + "|"
                  + ex.getRequestHeaders().getFirst("Authorization")
                  + "|"
                  + ex.getRequestHeaders().getFirst("X-Tenant-Id"));
          int code = 200;
          String body =
              "{\"value\":2.6,\"fetchedAt\":\"2026-10-22T08:00:00+07:00\","
                  + "\"dataSource\":\"CSDL đảng viên\"}";
          switch (mode.get()) {
            case "slow" -> {
              try {
                Thread.sleep(5_000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            case "error" -> {
              code = 500;
              body = "{}";
            }
            case "text" -> body = "{\"value\":\"không phải số\"}";
            default -> {}
          }
          byte[] b = body.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(code, b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    server.start();
    factory =
        new ResilientClientFactory(
            new StaticListableBeanFactory().getBeanProvider(RestClient.Builder.class),
            CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(4)
                    .minimumNumberOfCalls(4)
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofMillis(300))
                    .permittedNumberOfCallsInHalfOpenState(1)
                    .recordExceptions(
                        IOException.class, TimeoutException.class, HttpServerErrorException.class)
                    .build()),
            RetryRegistry.of(
                RetryConfig.custom()
                    .maxAttempts(2)
                    .waitDuration(Duration.ofMillis(10))
                    .retryExceptions(IOException.class)
                    .build()),
            TimeLimiterRegistry.of(
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).build()),
            BulkheadRegistry.ofDefaults());
    http =
        factory.create(
            HttpKrSourceValues.INSTANCE, "http://127.0.0.1:" + server.getAddress().getPort());
    client = new HttpKrSourceValues(http, () -> "svc-token", meters);
  }

  @AfterEach
  void stop() throws Exception {
    server.stop(0);
    factory.destroy();
  }

  @Test
  void layGiaTriMangTokenDichVuVaTenant() {
    SourceValue v = client.fetch(T, KR, CSDL);
    assertThat(v.value()).isEqualByComparingTo("2.6");
    assertThat(v.fetchedAt()).isEqualTo(Instant.parse("2026-10-22T01:00:00Z"));
    assertThat(v.dataSource()).isEqualTo(CSDL);
    assertThat(seen.get())
        .startsWith("keyResultId=" + KR + "&dataSource=CSDL%20%C4%91%E1%BA%A3ng%20vi%C3%AAn")
        .endsWith("|Bearer svc-token|" + T.value());
    assertThat(meters.get(HttpKrSourceValues.METRIC).tag("outcome", "ok").timer().count())
        .isEqualTo(1);
  }

  @Test
  void nguonCham5GiayBiCatO3Giay() {
    mode.set("slow");
    long start = System.nanoTime();
    assertThatThrownBy(() -> client.fetch(T, KR, CSDL))
        .isInstanceOf(KrSourceValuePort.Unavailable.class);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3_200));
    assertThat(
            meters
                .get(HttpKrSourceValues.METRIC)
                .tags("outcome", "error", "data_source", CSDL)
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void ngatMachMoSauChuoiLoiVaDongKhiNguonPhucHoi() throws Exception {
    mode.set("error");
    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> client.fetch(T, KR, CSDL))
          .isInstanceOf(KrSourceValuePort.Unavailable.class);
    }
    assertThat(http.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    int before = hits.get();
    mode.set("ok");
    assertThatThrownBy(() -> client.fetch(T, KR, CSDL))
        .isInstanceOf(KrSourceValuePort.Unavailable.class);
    assertThat(hits.get()).isEqualTo(before);
    Thread.sleep(350);
    assertThat(client.fetch(T, KR, CSDL).value()).isEqualByComparingTo("2.6");
    assertThat(http.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void thanKhongCoSoHoacThoiDiemSaiLaKhongDungDuoc() throws Exception {
    mode.set("text");
    assertThatThrownBy(() -> client.fetch(T, KR, CSDL))
        .isInstanceOf(KrSourceValuePort.Unavailable.class);
    ObjectMapper json = new ObjectMapper();
    assertThatThrownBy(
            () ->
                HttpKrSourceValues.parse(json.readTree("{\"value\":1,\"fetchedAt\":\"x\"}"), CSDL))
        .isInstanceOf(KrSourceValuePort.Unavailable.class);
    assertThatThrownBy(() -> HttpKrSourceValues.parse(null, CSDL))
        .isInstanceOf(KrSourceValuePort.Unavailable.class);
    SourceValue noTime = HttpKrSourceValues.parse(json.readTree("{\"value\":3}"), CSDL);
    assertThat(noTime.value()).isEqualTo(new BigDecimal("3"));
    assertThat(noTime.dataSource()).isEqualTo(CSDL);
    assertThat(noTime.fetchedAt()).isNotNull();
    mode.set("ok");
    assertThat(new HttpKrSourceValues(http, () -> "t", null).fetch(T, KR, CSDL).value())
        .isEqualByComparingTo("2.6");
  }
}
