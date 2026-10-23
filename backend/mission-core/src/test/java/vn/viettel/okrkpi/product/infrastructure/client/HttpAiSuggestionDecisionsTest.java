package vn.viettel.okrkpi.product.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.product.application.evidence.port.AiSuggestionDecisions;
import vn.viettel.okrkpi.product.application.evidence.port.AiSuggestionDecisions.Decision;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/** CORE-03-05: lời gọi {@code decideAiSuggestion} tới SVC-14 giả và cách đổi mã lỗi. */
class HttpAiSuggestionDecisionsTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID SUGGESTION = UUID.randomUUID();

  HttpServer server;
  final AtomicInteger status = new AtomicInteger(200);
  volatile long delayMs;
  final List<String> seen = new CopyOnWriteArrayList<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/ai/suggestions/",
        ex -> {
          String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          seen.add(
              ex.getRequestMethod()
                  + " "
                  + ex.getRequestURI().getPath()
                  + " "
                  + ex.getRequestHeaders().getFirst("Authorization")
                  + " "
                  + ex.getRequestHeaders().getFirst("X-Tenant-Id")
                  + " "
                  + ex.getRequestHeaders().getFirst("Idempotency-Key")
                  + " "
                  + body);
          try {
            Thread.sleep(delayMs);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(status.get(), out.length);
          ex.getResponseBody().write(out);
          ex.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  HttpAiSuggestionDecisions client(Duration timeout) {
    return new HttpAiSuggestionDecisions(
        "http://127.0.0.1:" + server.getAddress().getPort(), timeout);
  }

  void call(HttpAiSuggestionDecisions c) {
    c.decide(TENANT, SUGGESTION, Decision.SUA, "product.evidence_version:x", "Bearer u", "k-1");
  }

  @Test
  void guiQuyetDinhDuoiDanhTinhNguoiNop() {
    call(client(Duration.ofSeconds(5)));
    assertThat(seen)
        .containsExactly(
            "POST /api/v1/ai/suggestions/"
                + SUGGESTION
                + "/decision Bearer u "
                + TENANT.value()
                + " k-1 {\"decision\":\"SUA\",\"targetRef\":\"product.evidence_version:x\"}");
    // Đã có quyết định: coi là đã ghi.
    status.set(409);
    call(client(Duration.ofSeconds(5)));
  }

  @Test
  void doiMaLoiCuaSvc14() {
    status.set(404);
    assertThatThrownBy(() -> call(client(Duration.ofSeconds(5))))
        .isInstanceOf(AiSuggestionDecisions.SuggestionNotFoundException.class);
    status.set(403);
    assertThatThrownBy(() -> call(client(Duration.ofSeconds(5))))
        .isInstanceOf(AiSuggestionDecisions.SuggestionNotFoundException.class);
    status.set(503);
    assertThatThrownBy(() -> call(client(Duration.ofSeconds(5))))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            e -> {
              assertThat(e.dependency()).isEqualTo("ai-gateway");
              assertThat(e.reason()).isEqualTo(DependencyUnavailableException.Reason.IO_ERROR);
            });
  }

  @Test
  void quaThoiHanLaLoiThuLaiDuoc() {
    delayMs = 1_500;
    assertThatThrownBy(() -> call(client(Duration.ofMillis(300))))
        .isInstanceOfSatisfying(
            DependencyUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(DependencyUnavailableException.Reason.TIMEOUT));
  }

  @Test
  void khongGoiDuocLaLoiThuLaiDuoc() {
    server.stop(0);
    assertThatThrownBy(() -> call(client(Duration.ofSeconds(1))))
        .isInstanceOf(DependencyUnavailableException.class);
  }
}
