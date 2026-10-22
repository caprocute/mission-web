package vn.viettel.okrkpi.org.infrastructure.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.org.application.port.IdentityContextPort.StoredContext;
import vn.viettel.okrkpi.org.application.port.IntegrationUnavailableException;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;

/** DV-02-02: ghi và đọc thuộc tính ngữ cảnh qua Admin API Keycloak giả lập bằng máy chủ HTTP cục bộ. */
class KeycloakContextAdapterTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private final UUID known = UUID.randomUUID();
  private final AtomicReference<String> userBody = new AtomicReference<>();
  private final AtomicReference<String> written = new AtomicReference<>();
  private final AtomicInteger tokens = new AtomicInteger();
  private HttpServer server;
  private KeycloakContextAdapter adapter;

  @BeforeEach
  void start() throws Exception {
    userBody.set("{\"id\":\"" + known + "\",\"username\":\"an\",\"attributes\":{\"tenant_id\":[\"t\"],"
        + "\"mission_context_assignment\":[\"" + known + "\"],\"mission_context_valid_to\":[\"2020-01-01\"]}}");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/realms/mission/protocol/openid-connect/token", ex -> {
      tokens.incrementAndGet();
      respond(ex, 200, "{\"access_token\":\"t\",\"expires_in\":300}");
    });
    server.createContext("/admin/realms/mission/users/", ex -> {
      if (!ex.getRequestURI().getPath().endsWith(known.toString())) {
        respond(ex, 404, "{}");
      } else if ("PUT".equals(ex.getRequestMethod())) {
        written.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        respond(ex, 204, null);
      } else {
        respond(ex, 200, userBody.get());
      }
    });
    server.start();
    adapter = new KeycloakContextAdapter("http://127.0.0.1:" + server.getAddress().getPort(), "mission",
        "org-service", "bi-mat-kiem-thu", Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void ghiGopThuocTinhNguCanhVaGiuThuocTinhKhac() throws Exception {
    UUID assignment = UUID.randomUUID();
    UUID unit = UUID.randomUUID();
    adapter.write(known, new StoredContext(assignment, unit, List.of("member"), List.of("DANG"), null));
    JsonNode attrs = JSON.readTree(written.get()).get("attributes");
    assertThat(attrs.get("tenant_id").get(0).asText()).isEqualTo("t");
    assertThat(attrs.get("mission_context_unit").get(0).asText()).isEqualTo(unit.toString());
    assertThat(attrs.get("mission_context_assignment").get(0).asText()).isEqualTo(assignment.toString());
    assertThat(attrs.get("mission_context_flows").get(0).asText()).isEqualTo("DANG");
    assertThat(attrs.has("mission_context_valid_to")).isFalse();

    adapter.write(known, new StoredContext(assignment, unit, List.of(), List.of("NHA_NUOC"),
        LocalDate.of(2027, 1, 1)));
    assertThat(JSON.readTree(written.get()).get("attributes").get("mission_context_valid_to").get(0).asText())
        .isEqualTo("2027-01-01");
    assertThat(tokens.get()).isEqualTo(1);
    assertThat(adapter.lastAssignment(known)).contains(known);
  }

  @Test
  void nguoiDungKhongCoTaiKhoanTra404VaKhongChonSan() {
    UUID other = UUID.randomUUID();
    assertThatThrownBy(() -> adapter.write(other, new StoredContext(other, other, List.of(), List.of(), null)))
        .isInstanceOf(OrgNotFoundException.class);
    userBody.set("{\"attributes\":{\"mission_context_assignment\":[\"khong-phai-uuid\"]}}");
    assertThat(adapter.lastAssignment(known)).isEmpty();
  }

  @Test
  void chuaCauHinhHoacKeycloakNgungThiBao503() {
    KeycloakContextAdapter none = new KeycloakContextAdapter("", "mission", "org-service", "", Clock.systemUTC());
    assertThat(none.lastAssignment(known)).isEmpty();
    assertThatThrownBy(() -> none.write(known, new StoredContext(known, known, List.of(), List.of(), null)))
        .isInstanceOf(IntegrationUnavailableException.class);
    server.stop(0);
    KeycloakContextAdapter down = new KeycloakContextAdapter("http://127.0.0.1:1", "mission", "org-service", "",
        Clock.systemUTC());
    assertThatThrownBy(() -> down.write(known, new StoredContext(known, known, List.of(), List.of(), null)))
        .isInstanceOf(IntegrationUnavailableException.class);
  }

  private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body)
      throws java.io.IOException {
    byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, body == null ? -1 : b.length);
    if (body != null) {
      ex.getResponseBody().write(b);
    }
    ex.close();
  }
}
