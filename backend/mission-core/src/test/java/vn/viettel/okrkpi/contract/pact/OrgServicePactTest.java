package vn.viettel.okrkpi.contract.pact;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.shared.infrastructure.e2ee.OrgRecoveryKeyClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * QA-02 · bên tiêu dùng service với service: {@link OrgRecoveryKeyClient} của mission-core gọi
 * org-service (SVC-04) theo {@code src/test/resources/pacts/mission-core-org-service.json}. Máy giả
 * lập bên cung cấp chỉ trả phản hồi của hợp đồng khi yêu cầu khớp phương thức, đường dẫn và header
 * của tương tác; org-service xác minh cùng tệp ở {@code MissionCorePactIT}.
 */
class OrgServicePactTest {

  static final Path PACT = Path.of("src/test/resources/pacts/mission-core-org-service.json");

  private HttpServer server;
  private final List<String> mismatches = new ArrayList<>();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void docKhoaPhucHoiHienHanh() throws Exception {
    Pact.Interaction i = Pact.load(PACT).interaction("khoá phục hồi hiện hành của tenant");
    Optional<OrgRecoveryKeyClient.Current> current = client(i).current(tenant(i));
    assertThat(mismatches).isEmpty();
    assertThat(current).isPresent();
    assertThat(current.get().id().toString()).isEqualTo(i.response().at("/body/id").asText());
    assertThat(current.get().fingerprint()).hasSize(64);
  }

  @Test
  void tenantChuaCoKhoaThiRong() throws Exception {
    Pact.Interaction i = Pact.load(PACT).interaction("tenant chưa có khoá phục hồi");
    assertThat(client(i).current(tenant(i))).isEmpty();
    assertThat(mismatches).isEmpty();
  }

  private OrgRecoveryKeyClient client(Pact.Interaction i) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> respond(i, ex));
    server.start();
    String token = i.request().at("/headers/Authorization").asText().substring("Bearer ".length());
    return new OrgRecoveryKeyClient(
        RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), () -> token);
  }

  private void respond(Pact.Interaction i, HttpExchange ex) throws IOException {
    if (!ex.getRequestMethod().equals(i.method())) {
      mismatches.add("phương thức " + ex.getRequestMethod());
    }
    if (!ex.getRequestURI().getPath().equals(i.path())) {
      mismatches.add("đường dẫn " + ex.getRequestURI().getPath());
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = i.request().path("headers").fields();
        it.hasNext(); ) {
      Map.Entry<String, JsonNode> h = it.next();
      String actual = ex.getRequestHeaders().getFirst(h.getKey());
      if (actual == null
          || !PactMatcher.value(
              h.getKey(), h.getValue().asText(), actual, i.requestRules("header"))) {
        mismatches.add("header " + h.getKey());
      }
    }
    JsonNode res = i.response();
    byte[] body =
        res.has("body") ? res.get("body").toString().getBytes(StandardCharsets.UTF_8) : new byte[0];
    res.path("headers")
        .fields()
        .forEachRemaining(h -> ex.getResponseHeaders().add(h.getKey(), h.getValue().asText()));
    int status = mismatches.isEmpty() ? res.path("status").asInt(200) : 500;
    ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      ex.getResponseBody().write(body);
    }
    ex.close();
  }

  private static TenantId tenant(Pact.Interaction i) {
    return TenantId.of(UUID.fromString(i.request().at("/headers/X-Tenant-Id").asText()));
  }
}
