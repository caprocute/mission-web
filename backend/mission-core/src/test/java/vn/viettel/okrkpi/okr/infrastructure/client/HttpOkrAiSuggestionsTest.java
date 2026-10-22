package vn.viettel.okrkpi.okr.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
import vn.viettel.okrkpi.okr.application.port.OkrAiSuggestions;
import vn.viettel.okrkpi.shared.infrastructure.ai.AiSuggestionClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Cổng đọc gợi ý SVC-14 với stub HTTP (CORE-01-13). */
class HttpOkrAiSuggestionsTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID USER = UUID.randomUUID();

  private HttpServer server;
  private final Map<String, Integer> status = new ConcurrentHashMap<>();
  private final Map<String, String> bodies = new ConcurrentHashMap<>();
  private final Map<String, String> seenAuth = new ConcurrentHashMap<>();
  private HttpOkrAiSuggestions port;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/ai/suggestions/",
        ex -> {
          String id = ex.getRequestURI().getPath().replace("/api/v1/ai/suggestions/", "");
          seenAuth.put(id, String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
          seenAuth.put(
              id + "#tenant", String.valueOf(ex.getRequestHeaders().getFirst("X-Tenant-Id")));
          byte[] b = bodies.getOrDefault(id, "{}").getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(status.getOrDefault(id, 200), b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    port =
        new HttpOkrAiSuggestions(
            new AiSuggestionClient(base, Duration.ofSeconds(2)), new ObjectMapper());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void docGoiYCuaNguoiGoiVaDauRaJson() {
    UUID id = UUID.randomUUID();
    bodies.put(
        id.toString(),
        "{\"id\":\""
            + id
            + "\",\"kind\":\"MUC_TIEU\",\"decision\":\"CHAP_NHAN\",\"outputText\":"
            + "\"{\\\"objective\\\":{\\\"title\\\":\\\"Nâng chất lượng\\\"}}\"}");
    var s = port.find(TENANT, USER, id, "Bearer abc").orElseThrow();
    assertThat(s.kind()).isEqualTo("MUC_TIEU");
    assertThat(s.decision()).isEqualTo("CHAP_NHAN");
    assertThat(s.output()).containsKey("objective");
    assertThat(seenAuth).containsEntry(id.toString(), "Bearer abc");
    assertThat(seenAuth).containsEntry(id + "#tenant", TENANT.value().toString());
  }

  @Test
  void vanBanThuongVaThieuTruong() {
    UUID text = UUID.randomUUID();
    bodies.put(text.toString(), "{\"outputText\":\"Viết lại KR\",\"decision\":null}");
    var s = port.find(TENANT, USER, text, null).orElseThrow();
    assertThat(s.output()).containsEntry("text", "Viết lại KR");
    assertThat(s.decision()).isNull();
    UUID empty = UUID.randomUUID();
    assertThat(port.find(TENANT, USER, empty, null).orElseThrow().output()).isEmpty();
    UUID array = UUID.randomUUID();
    bodies.put(array.toString(), "{\"outputText\":\"[1,2]\"}");
    assertThat(port.find(TENANT, USER, array, null).orElseThrow().output())
        .containsEntry("text", "[1,2]");
  }

  @Test
  void khongCoHoacCuaNguoiKhacLaRongLoiLaKhongSan() {
    UUID missing = UUID.randomUUID();
    status.put(missing.toString(), 404);
    assertThat(port.find(TENANT, USER, missing, null)).isEmpty();
    UUID busy = UUID.randomUUID();
    status.put(busy.toString(), 503);
    assertThatThrownBy(() -> port.find(TENANT, USER, busy, null))
        .isInstanceOf(OkrAiSuggestions.Unavailable.class);
    server.stop(0);
    assertThatThrownBy(() -> port.find(TENANT, USER, UUID.randomUUID(), null))
        .isInstanceOf(OkrAiSuggestions.Unavailable.class);
  }
}
