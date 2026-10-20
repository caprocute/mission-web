package vn.viettel.okrkpi.shared.platformtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException.Reason;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;
import vn.viettel.okrkpi.shared.resilience.ResilientClientFactory;

/**
 * {@link ResilientClient} với cấu hình TL-01 thật của starter: mạch mở sau 20 lời gọi lỗi 5xx,
 * retry chỉ cho thao tác idempotent, timeout, bulkhead đầy.
 */
@SpringBootTest(
    classes = PlatformTestApplication.class,
    properties = {
      "resilience4j.timelimiter.instances.cham.timeoutDuration=150ms",
      "resilience4j.bulkhead.instances.hep.maxConcurrentCalls=1",
      "resilience4j.bulkhead.instances.hep.maxWaitDuration=0ms"
    })
class ResilientClientTest {

  private static final String BASE = "http://phu-thuoc.test";
  private static final IOException RESET = new IOException("Connection reset");

  @Autowired private ResilientClientFactory factory;
  @Autowired private BulkheadRegistry bulkheads;

  private record Bound(ResilientClient client, MockRestServiceServer server) {}

  private Bound bind(String instance) {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    return new Bound(factory.create(instance, builder.build()), server);
  }

  private static ResponseCreator ioError() {
    return request -> {
      throw RESET;
    };
  }

  @Test
  void macMoSauHaiMuoiLoiGoi5xxRoiTuChoiVoiRetryAfter() {
    Bound b = bind("mo-mach");
    b.server
        .expect(ExpectedCount.times(20), requestTo(BASE + "/ho-so"))
        .andRespond(withServerError());

    for (int i = 0; i < 20; i++) {
      assertThatThrownBy(() -> b.client.get("/ho-so", String.class))
          .isInstanceOf(HttpServerErrorException.class);
    }
    assertThat(b.client.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

    assertThatThrownBy(() -> b.client.get("/ho-so", String.class))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            e -> {
              assertThat(e.reason()).isEqualTo(Reason.CIRCUIT_OPEN);
              assertThat(e.dependency()).isEqualTo("mo-mach");
              assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(20));
              assertThat(e.retryAfterSeconds()).isEqualTo(20);
            });
    b.server.verify();
  }

  @Test
  void getLoiKetNoiThuLaiBaLan() {
    Bound b = bind("doc-lai");
    b.server
        .expect(ExpectedCount.times(3), requestTo(BASE + "/danh-muc"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(ioError());

    assertThatThrownBy(() -> b.client.get("/danh-muc", String.class))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.IO_ERROR));
    b.server.verify();
  }

  @Test
  void postKhongCoIdempotencyKeyChiGoiMotLan() {
    Bound b = bind("ghi-mot-lan");
    b.server
        .expect(ExpectedCount.once(), requestTo(BASE + "/ky-so"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(ioError());

    assertThatThrownBy(() -> b.client.post("/ky-so", "{}", null, String.class))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.IO_ERROR));
    b.server.verify();
  }

  @Test
  void postCoIdempotencyKeyDuocThuLai() {
    Bound b = bind("ghi-lap-lai");
    String key = "0192f5a0-7b1c-7cde-8f00-00000000abcd";
    b.server
        .expect(ExpectedCount.times(2), requestTo(BASE + "/ky-so"))
        .andExpect(header(ResilientClient.IDEMPOTENCY_KEY, key))
        .andRespond(ioError());
    b.server
        .expect(ExpectedCount.once(), requestTo(BASE + "/ky-so"))
        .andRespond(withSuccess("da-ky", MediaType.TEXT_PLAIN));

    assertThat(b.client.post("/ky-so", "{}", key, String.class)).isEqualTo("da-ky");
    b.server.verify();
  }

  @Test
  void quaThoiGianThanhPhuThuocKhongDungDuoc() {
    Bound b = bind("cham");
    b.server
        .expect(ExpectedCount.manyTimes(), requestTo(BASE + "/bao-cao"))
        .andRespond(
            request -> {
              try {
                Thread.sleep(2_000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return withSuccess().createResponse(request);
            });

    long start = System.nanoTime();
    assertThatThrownBy(() -> b.client.get("/bao-cao", String.class))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.TIMEOUT));
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void bulkheadDayTuChoiNgay() {
    Bound b = bind("hep");
    Bulkhead hep = bulkheads.bulkhead("hep");
    assertThat(hep.tryAcquirePermission()).isTrue();
    try {
      assertThatThrownBy(() -> b.client.get("/x", String.class))
          .isInstanceOfSatisfying(
              DependencyUnavailableException.class,
              e -> {
                assertThat(e.reason()).isEqualTo(Reason.BULKHEAD_FULL);
                assertThat(e.retryAfterSeconds()).isEqualTo(1);
              });
    } finally {
      hep.releasePermission();
    }
  }

  @Test
  void nguCanhNhatKyDiTheoLoiGoi() {
    Bound b = bind("ngu-canh");
    AtomicReference<String> seen = new AtomicReference<>();
    b.server
        .expect(requestTo(BASE + "/ping"))
        .andRespond(
            request -> {
              seen.set(MDC.get("trace_id"));
              return withSuccess("pong", MediaType.TEXT_PLAIN).createResponse(request);
            });
    MDC.put("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736");
    try {
      assertThat(b.client.get("/ping", String.class)).isEqualTo("pong");
    } finally {
      MDC.remove("trace_id");
    }
    assertThat(seen.get()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
  }

  @Test
  void khongTuChoiLoi4xxVaKhongThuLai() {
    Bound b = bind("loi-4xx");
    b.server
        .expect(ExpectedCount.once(), requestTo(BASE + "/khong-co"))
        .andRespond(withResourceNotFound());

    assertThatThrownBy(() -> b.client.get("/khong-co", String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
    b.server.verify();
    assertThat(b.client.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
  }
}
