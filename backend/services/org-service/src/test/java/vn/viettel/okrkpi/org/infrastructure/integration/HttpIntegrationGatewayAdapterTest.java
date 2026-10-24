package vn.viettel.okrkpi.org.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HT-07: lời gọi tới cổng tích hợp kèm token dịch vụ client credentials (lấy một lần, dùng lại tới gần hết hạn) và
 * tenant đích ở {@code X-Tenant-Id}.
 */
class HttpIntegrationGatewayAdapterTest {

  private final AtomicInteger tokens = new AtomicInteger();
  private final List<String> authorization = new CopyOnWriteArrayList<>();
  private final List<String> forms = new CopyOnWriteArrayList<>();
  private final List<String> tenants = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private String base;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/realms/mission/protocol/openid-connect/token", ex -> {
      tokens.incrementAndGet();
      forms.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(ex, 200, "{\"access_token\":\"token-dich-vu\",\"expires_in\":300}");
    });
    server.createContext("/internal/v1/integ/", ex -> {
      authorization.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
      tenants.add(String.valueOf(ex.getRequestHeaders().getFirst("X-Tenant-Id")));
      respond(ex, 202, "{}");
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void kemTokenDichVuVaDungLai() {
    HttpIntegrationGatewayAdapter adapter =
        new HttpIntegrationGatewayAdapter(base, base, "mission", "org-service", "bi-mat-kiem-thu", Clock.systemUTC());
    UUID tenant = UUID.randomUUID();
    adapter.requestRun(tenant, UUID.randomUUID(), "HRM");
    adapter.requestRun(tenant, UUID.randomUUID(), "CBCCVC");

    assertThat(authorization).containsExactly("Bearer token-dich-vu", "Bearer token-dich-vu");
    assertThat(tokens).hasValue(1);
    assertThat(tenants).containsOnly(tenant.toString());
    assertThat(forms.get(0)).contains("grant_type=client_credentials", "client_id=org-service");
  }

  @Test
  void khongCauHinhKeycloakThiGoiKhongToken() {
    HttpIntegrationGatewayAdapter adapter =
        new HttpIntegrationGatewayAdapter(base, "", "mission", "org-service", "", Clock.systemUTC());
    adapter.requestRun(UUID.randomUUID(), UUID.randomUUID(), "HRM");

    assertThat(authorization).containsExactly("null");
    assertThat(tokens).hasValue(0);
  }

  private static void respond(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }
}
