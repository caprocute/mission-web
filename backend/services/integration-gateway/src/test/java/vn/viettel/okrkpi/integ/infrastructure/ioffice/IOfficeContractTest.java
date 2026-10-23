package vn.viettel.okrkpi.integ.infrastructure.ioffice;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.integ.application.InboundConnector.Page;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.integ.domain.Protocol;

/**
 * Hợp đồng phía tiêu dùng của SVC-13 với iOffice (DV-13-04): bộ kết nối chạy với iOffice giả lập ở
 * {@code src/test/resources/ioffice-gia-lap} qua REST và SOAP, và mọi tương tác trong tệp Pact
 * {@code pacts/integration-gateway-ioffice.json} được phát lại để kiểm yêu cầu mã gửi đi khớp hợp
 * đồng. Pact Broker của QA-02 chưa có nên hợp đồng lưu thành tệp trong repo.
 */
class IOfficeContractTest {

  static final WireMockServer GIA_LAP =
      new WireMockServer(
          wireMockConfig().dynamicPort().usingFilesUnderClasspath("ioffice-gia-lap"));
  static final WireMockServer PACT = new WireMockServer(wireMockConfig().dynamicPort());

  @BeforeAll
  static void start() {
    GIA_LAP.start();
    PACT.start();
  }

  @AfterAll
  static void stop() {
    GIA_LAP.stop();
    PACT.stop();
  }

  final ObjectMapper json = new ObjectMapper();
  final IOfficeClient client =
      new IOfficeClient(
          HttpClient.newHttpClient(),
          json,
          path -> "ioffice/token".equals(path) ? Optional.of("ma-gia-lap") : Optional.empty(),
          Duration.ofSeconds(5));
  final IOfficeConnector source =
      new IOfficeConnector("IOFFICE", client, Duration.ofSeconds(60), new IOfficeTranslator(json));

  static Connector connector(Protocol protocol, String endpoint, String secretPath) {
    return new Connector(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "IOFFICE",
        protocol,
        endpoint,
        secretPath,
        Direction.VAO,
        null,
        true);
  }

  @Test
  void keoQuaRestTheoConTroThoiDiem() throws IOException {
    assertThat(source.code()).isEqualTo("IOFFICE");
    assertThat(source.stream()).isEqualTo(IOfficeConnector.STREAM);
    assertThat(source.period()).isEqualTo(Duration.ofSeconds(60));
    assertThat(source.translator()).isInstanceOf(IOfficeTranslator.class);
    Connector c = connector(Protocol.REST, GIA_LAP.baseUrl() + "/", "ioffice/token");

    Page first = source.fetch(c, source.initialCursor());
    InboundMessage m = first.items().get(0);
    assertThat(first.items()).hasSize(1);
    assertThat(m.externalMsgId()).isEqualTo("IO-2026-0001:1");
    assertThat(m.messageType()).isEqualTo(IOfficeConnector.TYPE_JSON);
    assertThat(json.readTree(m.raw()).path("soKyHieu").asText()).isEqualTo("12/CT-UBND");
    assertThat(first.nextCursor()).isEqualTo("2026-10-20T01:00:00Z");
    assertThat(first.sourceTime()).isEqualTo(Instant.parse("2026-10-20T01:00:00Z"));

    Page empty = source.fetch(c, first.nextCursor());
    assertThat(empty.items()).isEmpty();
    assertThat(empty.nextCursor()).isEqualTo(first.nextCursor());
    assertThat(empty.sourceTime()).isNull();

    assertThatThrownBy(
            () -> source.fetch(connector(Protocol.REST, GIA_LAP.baseUrl(), null), EPOCH()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("404");
  }

  @Test
  void keoQuaSoapChuanHoaVeCungDangVoiRest() throws IOException {
    Connector c = connector(Protocol.SOAP, GIA_LAP.baseUrl(), "ioffice/token");
    Page first = source.fetch(c, EPOCH());
    assertThat(first.items()).hasSize(1);
    InboundMessage m = first.items().get(0);
    assertThat(m.externalMsgId()).isEqualTo("IO-2026-0002:3");
    JsonNode doc = json.readTree(m.raw());
    assertThat(doc.path("soKyHieu").asText()).isEqualTo("08/KH-UBND");
    assertThat(doc.path("noiNhan")).hasSize(2);
    assertThat(doc.path("noiNhan").get(1).path("maDonVi").asText()).isEqualTo("DV-01");
    assertThat(doc.path("tepDinhKem").get(0).path("tenTep").asText()).isEqualTo("08-KH.pdf");
    assertThat(first.nextCursor())
        .as("SOAP không trả con trỏ tiếp theo thì lấy thời điểm cập nhật mới nhất")
        .isEqualTo("2026-10-20T02:30:00Z");

    assertThatThrownBy(() -> source.fetch(c, "2000-01-01T00:00:00Z"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Fault");
    assertThatThrownBy(() -> client.fromSoap("<a><b>".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(
            () ->
                client.fromSoap(
                    ("<!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                            + "<a><vanBan><vanBanId>&x;</vanBanId></vanBan></a>")
                        .getBytes(StandardCharsets.UTF_8)))
        .as("bộ đọc XML không nạp thực thể ngoài")
        .isInstanceOf(IOException.class);
  }

  @Test
  void goiEdxmlVaMaVanBanSai() throws IOException {
    PACT.resetAll();
    PACT.stubFor(
        any(anyUrl())
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"duLieu\":[{\"vanBanId\":\"IO-E\",\"phienBan\":1,\"goiEdxml\":\""
                            + java.util.Base64.getEncoder()
                                .encodeToString("<edXML/>".getBytes(StandardCharsets.UTF_8))
                            + "\"}]}")));
    Connector c = connector(Protocol.REST, PACT.baseUrl(), null);
    Page p = source.fetch(c, EPOCH());
    assertThat(p.items().get(0).messageType()).isEqualTo(IOfficeConnector.TYPE_EDXML);
    assertThat(new String(p.items().get(0).raw(), StandardCharsets.UTF_8)).isEqualTo("<edXML/>");
    assertThat(p.nextCursor()).as("không có thời điểm thì giữ con trỏ").isEqualTo(EPOCH());

    PACT.resetAll();
    PACT.stubFor(
        any(anyUrl())
            .willReturn(
                aResponse()
                    .withBody("{\"duLieu\":[{\"vanBanId\":\"IO-E\",\"goiEdxml\":\"%%%\"}]}")));
    assertThatThrownBy(() -> source.fetch(c, EPOCH())).isInstanceOf(IOException.class);
    PACT.resetAll();
    PACT.stubFor(any(anyUrl()).willReturn(aResponse().withBody("{\"duLieu\":[{}]}")));
    assertThatThrownBy(() -> source.fetch(c, EPOCH())).isInstanceOf(IOException.class);
    PACT.resetAll();
    PACT.stubFor(
        any(anyUrl()).willReturn(aResponse().withBody(new byte[IOfficeClient.MAX_BYTES + 1])));
    assertThatThrownBy(() -> source.fetch(c, EPOCH()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("giới hạn");
  }

  /** Phát lại từng tương tác Pact: yêu cầu thật của bộ kết nối phải khớp yêu cầu trong hợp đồng. */
  @Test
  void yeuCauGuiDiKhopHopDongPact() throws IOException {
    JsonNode pact;
    try (InputStream in =
        getClass().getResourceAsStream("/pacts/integration-gateway-ioffice.json")) {
      pact = json.readTree(in);
    }
    assertThat(pact.path("metadata").path("pactSpecification").path("version").asText())
        .isEqualTo("3.0.0");
    JsonNode interactions = pact.path("interactions");
    assertThat(interactions).hasSize(2);
    for (JsonNode it : interactions) {
      JsonNode state = it.path("providerStates").get(0).path("params");
      JsonNode req = it.path("request");
      JsonNode res = it.path("response");
      PACT.resetAll();
      ResponseDefinitionBuilder reply = aResponse().withStatus(res.path("status").asInt());
      res.path("headers")
          .properties()
          .forEach(h -> reply.withHeader(h.getKey(), h.getValue().asText()));
      JsonNode body = res.path("body");
      reply.withBody(body.isTextual() ? body.asText() : json.writeValueAsString(body));
      PACT.stubFor(any(anyUrl()).willReturn(reply));

      Protocol protocol = Protocol.valueOf(state.path("giaoThuc").asText());
      JsonNode page =
          client.incoming(
              connector(protocol, PACT.baseUrl(), "ioffice/token"),
              state.path("tuThoiDiem").asText(),
              IOfficeConnector.PAGE);
      assertThat(page.path("duLieu")).as(it.path("description").asText()).hasSize(1);

      List<LoggedRequest> sent =
          PACT.findAll(com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor(anyUrl()));
      assertThat(sent).hasSize(1);
      LoggedRequest r = sent.get(0);
      assertThat(r.getMethod().getName()).isEqualTo(req.path("method").asText());
      assertThat(r.getUrl()).startsWith(req.path("path").asText());
      for (Map.Entry<String, JsonNode> q : req.path("query").properties()) {
        assertThat(r.queryParameter(q.getKey()).values())
            .as("tham số " + q.getKey())
            .containsExactly(q.getValue().get(0).asText());
      }
      for (Map.Entry<String, JsonNode> h : req.path("headers").properties()) {
        assertThat(r.getHeader(h.getKey()))
            .as("tiêu đề " + h.getKey())
            .isEqualTo(h.getValue().asText());
      }
      if (req.has("body")) {
        assertThat(r.getBodyAsString()).isEqualTo(req.path("body").asText());
      }
    }
  }

  static String EPOCH() {
    return IOfficeConnector.EPOCH;
  }
}
