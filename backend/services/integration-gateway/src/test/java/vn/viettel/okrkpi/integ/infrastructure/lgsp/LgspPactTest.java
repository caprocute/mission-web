package vn.viettel.okrkpi.integ.infrastructure.lgsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.integ.application.InboundConnector.Page;
import vn.viettel.okrkpi.integ.application.MappingXrefService;
import vn.viettel.okrkpi.integ.application.TranslationContext;
import vn.viettel.okrkpi.integ.application.port.KtxhValues;
import vn.viettel.okrkpi.integ.contract.PactMockProvider;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểm thử Pact phía tiêu dùng của SVC-13 với LGSP, chỉ tiêu kinh tế xã hội (DV-13-11): bộ kết nối
 * và translator chạy với đối tác giả lập dựng từ {@code pacts/integration-gateway-lgsp.json}.
 */
class LgspPactTest {

  static final PactMockProvider DOI_TAC = PactMockProvider.of("integration-gateway-lgsp.json");

  @AfterAll
  static void stop() {
    DOI_TAC.close();
  }

  @Test
  void keoChiTieuVaGhiGiaTriTheoHopDong() throws IOException {
    ObjectMapper json = new ObjectMapper();
    Instant now = Instant.parse("2026-10-23T03:00:00Z");
    KtxhValues values = mock(KtxhValues.class);
    LgspIndicatorTranslator translator =
        new LgspIndicatorTranslator(values, json, Clock.fixed(now, ZoneOffset.UTC));
    LgspIndicatorConnector source =
        new LgspIndicatorConnector(
            "LGSP",
            HttpClient.newHttpClient(),
            p -> Optional.of("ma-gia-lap"),
            json,
            "/api/chi-tieu-ktxh",
            200,
            Duration.ofHours(1),
            Duration.ofSeconds(5),
            translator);
    TenantId tenant = TenantId.of(UUID.randomUUID());
    Connector c =
        new Connector(
            UUID.randomUUID(),
            tenant.value(),
            "LGSP",
            Protocol.REST,
            DOI_TAC.baseUrl(),
            "lgsp/token",
            Direction.VAO,
            null,
            true);
    Page page = source.fetch(c, source.initialCursor());
    assertThat(page.items()).hasSize(1);
    assertThat(page.nextCursor()).isEqualTo("2026-07-15T02:00:00Z");
    translator.translate(
        new TranslationContext(
            tenant, c, LocalDate.of(2026, 10, 23), mock(MappingXrefService.class)),
        page.items().get(0));
    ArgumentCaptor<KtxhValues.Value> v = ArgumentCaptor.forClass(KtxhValues.Value.class);
    verify(values).upsert(eq(tenant), any(), eq(c.id()), v.capture(), eq(now));
    assertThat(v.getValue().indicatorCode()).isEqualTo("GRDP");
    assertThat(v.getValue().value()).isEqualByComparingTo(new BigDecimal("7.2"));
    assertThat(v.getValue().publishedAt()).isEqualTo(Instant.parse("2026-07-15T02:00:00Z"));
    DOI_TAC.verify();
  }
}
