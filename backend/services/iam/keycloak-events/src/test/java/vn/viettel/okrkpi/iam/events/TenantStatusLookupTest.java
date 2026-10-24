package vn.viettel.okrkpi.iam.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tra trạng thái tenant cho bước chặn đăng nhập (TL-04 mục 11.4): đệm, token, registry lỗi thì không chặn. */
class TenantStatusLookupTest {

  private HttpServer server;
  private final Map<String, String> statuses = new ConcurrentHashMap<>();
  private final AtomicInteger resolveCalls = new AtomicInteger();
  private final AtomicInteger tokenCalls = new AtomicInteger();
  private final AtomicLong now = new AtomicLong(1_000_000);
  private volatile boolean registryDown;
  private volatile String lastAuth;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", ex -> {
      tokenCalls.incrementAndGet();
      String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      boolean ok = form.contains("grant_type=client_credentials") && form.contains("client_id=kc-cell")
          && form.contains("client_secret=bi-mat-gia");
      reply(ex, ok ? 200 : 401, ok ? "{\"access_token\":\"tok-" + tokenCalls.get() + "\",\"expires_in\":300}" : "{}");
    });
    server.createContext("/api/v1/registry/resolve", ex -> {
      resolveCalls.incrementAndGet();
      lastAuth = ex.getRequestHeaders().getFirst("Authorization");
      String tenant = ex.getRequestURI().getQuery().replaceFirst("^tenant=", "");
      String s = statuses.get(tenant);
      if (registryDown) {
        reply(ex, 503, "{}");
      } else if (s == null) {
        reply(ex, 404, "{}");
      } else {
        reply(ex, 200, "{\"tenantCode\":\"" + tenant + "\",\"status\":\"" + s + "\"}");
      }
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static void reply(HttpExchange ex, int code, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.sendResponseHeaders(code, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  private TenantStatusLookup lookup(String registry) {
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    Map<String, String> env = Map.of(
        "MISSION_TENANT_REGISTRY_URL", registry == null ? base + "/" : registry,
        "MISSION_TENANT_REGISTRY_TOKEN_URL", base + "/token",
        "MISSION_TENANT_REGISTRY_CLIENT_ID", "kc-cell",
        "MISSION_TENANT_REGISTRY_CLIENT_SECRET", "bi-mat-gia",
        "MISSION_TENANT_REGISTRY_TIMEOUT_MS", "2000");
    return new TenantStatusLookup(env::get, now::get);
  }

  @Test
  void khongCauHinhThiKhongKiem() {
    TenantStatusLookup l = lookup("");
    assertThat(l.enabled()).isFalse();
    assertThat(l.status("so-tu-phap")).isEmpty();
    assertThat(resolveCalls).hasValue(0);
  }

  @Test
  void traTrangThaiBangTokenDichVuVaLuuDem() {
    statuses.put("so-tu-phap", "TAM_NGUNG");
    TenantStatusLookup l = lookup(null);
    assertThat(l.status("so-tu-phap")).contains("TAM_NGUNG");
    assertThat(lastAuth).isEqualTo("Bearer tok-1");
    statuses.put("so-tu-phap", "DANG_VAN_HANH");
    now.addAndGet(29_000);
    assertThat(l.status("so-tu-phap")).as("còn trong hạn đệm").contains("TAM_NGUNG");
    now.addAndGet(2_000);
    assertThat(l.status("so-tu-phap")).contains("DANG_VAN_HANH");
    assertThat(resolveCalls).hasValue(2);
    assertThat(tokenCalls).as("token dùng lại tới gần hạn").hasValue(1);
    assertThat(l.status(null)).isEmpty();
    assertThat(l.status(" ")).isEmpty();
  }

  @Test
  void registryLoiThiDungDemCuHoacKhongChan() {
    statuses.put("so-noi-vu", "TAM_NGUNG");
    TenantStatusLookup l = lookup(null);
    assertThat(l.status("so-noi-vu")).contains("TAM_NGUNG");
    registryDown = true;
    now.addAndGet(60_000);
    assertThat(l.status("so-noi-vu")).as("đệm cũ").contains("TAM_NGUNG");
    assertThat(l.status("tenant-chua-tra")).as("không có đệm thì không chặn").isEqualTo(Optional.empty());
  }
}
