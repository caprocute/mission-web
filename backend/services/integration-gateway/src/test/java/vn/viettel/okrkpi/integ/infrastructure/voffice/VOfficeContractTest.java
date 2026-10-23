package vn.viettel.okrkpi.integ.infrastructure.voffice;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.integ.application.InboundConnector.Page;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSource;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.Protocol;

/**
 * Kiểm bộ kết nối với hợp đồng phía tiêu dùng công bố ở {@code src/test/resources/voffice-gia-lap}
 * (ánh xạ WireMock dùng lại cho QA-02): sai lệch giữa mã và hợp đồng làm kiểm thử này đỏ.
 */
class VOfficeContractTest {

  static final WireMockServer GIA_LAP =
      new WireMockServer(
          wireMockConfig().dynamicPort().usingFilesUnderClasspath("voffice-gia-lap"));

  @BeforeAll
  static void start() {
    GIA_LAP.start();
  }

  @AfterAll
  static void stop() {
    GIA_LAP.stop();
  }

  final ObjectMapper json = new ObjectMapper();
  final VOfficeClient client =
      new VOfficeClient(
          HttpClient.newHttpClient(),
          json,
          path -> "voffice/token".equals(path) ? Optional.of("ma-gia-lap") : Optional.empty(),
          Duration.ofSeconds(5));

  Connector connector(String secretPath, String endpoint) {
    return new Connector(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "VOFFICE",
        Protocol.REST,
        endpoint,
        secretPath,
        Direction.HAI_CHIEU,
        null,
        true);
  }

  @Test
  void keoVanBanChiDaoTheoConTro() throws IOException {
    VOfficeConnector source =
        new VOfficeConnector(
            "VOFFICE", client, Duration.ofSeconds(30), new VOfficeTranslator(json));
    Connector c = connector("voffice/token", GIA_LAP.baseUrl() + "/");
    assertThat(source.stream()).isEqualTo(VOfficeConnector.STREAM);
    assertThat(source.period()).isEqualTo(Duration.ofSeconds(30));
    assertThat(source.translator()).isInstanceOf(VOfficeTranslator.class);

    Page first = source.fetch(c, source.initialCursor());
    assertThat(first.items()).hasSize(1);
    assertThat(first.items().get(0).externalMsgId()).isEqualTo("VB-2026-0001:1");
    assertThat(first.items().get(0).messageType()).isEqualTo(VOfficeConnector.TYPE_JSON);
    assertThat(first.nextCursor()).isEqualTo("2026-10-20T01:00:00Z");
    assertThat(first.sourceTime()).isEqualTo(Instant.parse("2026-10-20T01:00:00Z"));

    Page empty = source.fetch(c, first.nextCursor());
    assertThat(empty.items()).isEmpty();
    assertThat(empty.nextCursor()).isEqualTo(first.nextCursor());

    assertThatThrownBy(() -> source.fetch(connector(null, GIA_LAP.baseUrl()), EPOCH()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("404");
    assertThatThrownBy(() -> source.fetch(connector(null, "ftp://x.invalid"), EPOCH()))
        .isInstanceOf(IOException.class);
  }

  static String EPOCH() {
    return VOfficeConnector.EPOCH;
  }

  @Test
  void traCuuVanBanDaBanHanh() throws IOException {
    VOfficeIssuedDocuments docs = new VOfficeIssuedDocuments("VOFFICE", client);
    assertThat(docs.connectorCode()).isEqualTo("VOFFICE");
    IssuedDocumentSource.Result r =
        docs.search(connector("voffice/token", GIA_LAP.baseUrl()), "DV-01", null, 20, null);
    assertThat(r.nextCursor()).isNull();
    assertThat(r.items())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.ref()).isEqualTo("47-BC/BTCĐUK");
              assertThat(d.docDate()).isEqualTo(LocalDate.of(2026, 10, 19));
              assertThat(d.signerName()).isEqualTo("Trần Thị Bình");
              assertThat(d.signatureValid()).isTrue();
            });
  }
}
