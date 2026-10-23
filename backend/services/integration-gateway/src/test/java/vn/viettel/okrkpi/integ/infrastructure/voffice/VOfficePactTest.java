package vn.viettel.okrkpi.integ.infrastructure.voffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.integ.application.CanonicalEvent;
import vn.viettel.okrkpi.integ.application.InboundConnector.Page;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSource;
import vn.viettel.okrkpi.integ.application.IssuedDocumentTransfer;
import vn.viettel.okrkpi.integ.application.MappingXrefService;
import vn.viettel.okrkpi.integ.application.TranslationContext;
import vn.viettel.okrkpi.integ.contract.Pact;
import vn.viettel.okrkpi.integ.contract.PactMockProvider;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.vtokr.integ.v1.VanBanChiDaoDaNhan;

/**
 * Kiểm thử Pact phía tiêu dùng của SVC-13 với vOffice (DV-13-11): bộ kết nối thật chạy với đối tác
 * giả lập dựng từ {@code pacts/integration-gateway-voffice.json}; yêu cầu gửi lệch hợp đồng (thiếu,
 * thừa tham số, sai header) làm kiểm thử đỏ. Tin nhắn đẩy ({@code
 * integration-gateway-voffice-tin-nhan.json}) đi qua chính translator của chiều vào.
 */
class VOfficePactTest {

  static final PactMockProvider DOI_TAC = PactMockProvider.of("integration-gateway-voffice.json");

  @AfterAll
  static void stop() {
    DOI_TAC.close();
  }

  @BeforeEach
  void reset() {
    DOI_TAC.reset();
  }

  final ObjectMapper json = new ObjectMapper();
  final VOfficeClient client =
      new VOfficeClient(
          HttpClient.newHttpClient(),
          json,
          path -> "voffice/token".equals(path) ? Optional.of("ma-gia-lap") : Optional.empty(),
          Duration.ofSeconds(5));
  final Connector connector =
      new Connector(
          UUID.randomUUID(),
          UUID.randomUUID(),
          "VOFFICE",
          Protocol.REST,
          DOI_TAC.baseUrl(),
          "voffice/token",
          Direction.HAI_CHIEU,
          null,
          true);

  @Test
  void keoVanBanChiDaoTheoHopDong() throws IOException {
    VOfficeConnector source =
        new VOfficeConnector(
            "VOFFICE", client, Duration.ofSeconds(30), new VOfficeTranslator(json));
    Page first = source.fetch(connector, source.initialCursor());
    assertThat(first.items())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.externalMsgId()).isEqualTo("VB-2026-0001:1");
              assertThat(m.messageType()).isEqualTo(VOfficeConnector.TYPE_JSON);
            });
    assertThat(first.nextCursor()).isEqualTo("2026-10-20T01:00:00Z");
    assertThat(source.fetch(connector, first.nextCursor()).items()).isEmpty();
    DOI_TAC.verify(
        List.of(
            "kéo trang văn bản chỉ đạo đầu tiên theo con trỏ thời điểm",
            "kéo sau con trỏ cuối trả trang rỗng"));
  }

  @Test
  void traCuuVaTaiTepGocTheoHopDong() throws IOException {
    VOfficeIssuedDocuments docs = new VOfficeIssuedDocuments("VOFFICE", client, 1_000_000);
    IssuedDocumentSource.Result r = docs.search(connector, "DV-01", null, 20, null);
    assertThat(r.items())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.ref()).isEqualTo("47-BC/BTCĐUK");
              assertThat(d.docDate()).isEqualTo(LocalDate.of(2026, 10, 19));
              assertThat(d.signatureValid()).isTrue();
            });
    IssuedDocumentSource.OriginalFile f =
        docs.fetch(connector, "DV-01", "47-BC/BTCĐUK").orElseThrow();
    assertThat(f.mediaType()).isEqualTo("application/pdf");
    assertThat(IssuedDocumentTransfer.signatureValid(f))
        .as("băm SHA-256 của hợp đồng khớp nội dung tệp của hợp đồng")
        .isTrue();
    assertThat(docs.fetch(connector, "DV-01", "99-BC/KHONG-CO")).isEmpty();
    DOI_TAC.verify(
        List.of(
            "tra cứu văn bản đã ban hành của đơn vị",
            "siêu dữ liệu tệp gốc của văn bản đã ban hành",
            "nội dung tệp gốc của văn bản đã ban hành",
            "văn bản không thuộc đơn vị hoặc không có thì trả 404"));
  }

  @Test
  void yeuCauLechHopDongBiPhatHien() throws Exception {
    HttpClient http = HttpClient.newHttpClient();
    http.send(
        HttpRequest.newBuilder(
                URI.create(
                    DOI_TAC.baseUrl()
                        + "/api/v2/directives?updatedSince=1970-01-01T00:00:00Z&limit=500"))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ma-gia-lap")
            .build(),
        HttpResponse.BodyHandlers.discarding());
    assertThatThrownBy(DOI_TAC::verify)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("yêu cầu không có trong hợp đồng voffice-van-ban")
        .hasMessageContaining("tương tác chưa được gọi");
  }

  @Test
  void tinNhanDayDiQuaTranslator() throws IOException {
    Pact pact = Pact.load("integration-gateway-voffice-tin-nhan.json");
    Pact.Message m = pact.messages().get(0);
    TenantId tenant = TenantId.of(UUID.randomUUID());
    MappingXrefService mappings = mock(MappingXrefService.class);
    UUID unit = UUID.randomUUID();
    UUID doc = UUID.randomUUID();
    when(mappings.resolve(
            eq(tenant), eq(connector.id()), eq(EntityType.DON_VI), eq("DV-01"), any()))
        .thenReturn(Optional.of(unit));
    when(mappings.resolveOrCreate(
            eq(tenant),
            eq(connector.id()),
            eq(EntityType.VAN_BAN),
            eq("VB-2026-0001"),
            any(),
            any()))
        .thenReturn(doc);
    String id = m.contents().path("id").asText() + ":" + m.contents().path("version").asInt();
    List<CanonicalEvent> events =
        new VOfficeTranslator(json)
            .translate(
                new TranslationContext(tenant, connector, LocalDate.of(2026, 10, 23), mappings),
                new InboundMessage(
                    id,
                    m.metadata().path("kieuThongDiep").asText(),
                    json.writeValueAsBytes(m.contents())));
    assertThat(VOfficeConnector.TYPE_JSON).isEqualTo(m.metadata().path("kieuThongDiep").asText());
    assertThat(events)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.topic()).isEqualTo(VOfficeTranslator.TOPIC);
              assertThat(e.aggregateId()).isEqualTo(doc);
              VanBanChiDaoDaNhan p = (VanBanChiDaoDaNhan) e.payload();
              assertThat(p.getDocNumber()).isEqualTo("12/CT-UBND");
              assertThat(p.getAggregateVersion()).isEqualTo(1);
            });
  }
}
