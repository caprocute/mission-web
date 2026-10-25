package vn.viettel.okrkpi.scoring.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Token client dịch vụ gắn vào lời gọi mission-core và được giữ tới gần hết hạn (QA-06). */
class ServiceTokenTest {

  private static HttpServer server;
  private static String base;
  private static final AtomicInteger ISSUED = new AtomicInteger();
  private static final List<String> AUTH = new CopyOnWriteArrayList<>();

  @BeforeAll
  static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        ex -> {
          String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          byte[] body =
              (form.contains("grant_type=client_credentials")
                          && form.contains("client_id=scoring-service")
                      ? "{\"access_token\":\"t"
                          + ISSUED.incrementAndGet()
                          + "\",\"expires_in\":300}"
                      : "{}")
                  .getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, body.length);
          ex.getResponseBody().write(body);
          ex.close();
        });
    server.createContext(
        "/api",
        ex -> {
          AUTH.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
          ex.sendResponseHeaders(204, -1);
          ex.close();
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  private static void call(ServiceToken token) {
    RestClient.builder()
        .baseUrl(base)
        .requestInterceptor(token.interceptor())
        .build()
        .get()
        .uri("/api/x")
        .retrieve()
        .toBodilessEntity();
  }

  @Test
  void ganTokenVaGiuChoLanGoiSau() {
    AUTH.clear();
    ServiceToken token =
        new ServiceToken(
            RestClient.create(), base + "/token", "scoring-service", "bi-mat", Clock.systemUTC());
    call(token);
    call(token);
    assertThat(AUTH).hasSize(2).allMatch(a -> a.startsWith("Bearer t")).containsOnly(AUTH.get(0));
    assertThat(ISSUED).hasValue(1);
  }

  @Test
  void chuaKhaiClientThiKhongGanToken() {
    AUTH.clear();
    ServiceToken token =
        new ServiceToken(RestClient.create(), "", "scoring-service", "", Clock.systemUTC());
    assertThat(token.configured()).isFalse();
    call(token);
    assertThat(AUTH).containsExactly("null");
  }
}
