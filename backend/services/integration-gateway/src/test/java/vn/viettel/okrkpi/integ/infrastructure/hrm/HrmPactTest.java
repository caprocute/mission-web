package vn.viettel.okrkpi.integ.infrastructure.hrm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.integ.application.InboundConnector.Page;
import vn.viettel.okrkpi.integ.application.IndicatorSource;
import vn.viettel.okrkpi.integ.contract.PactMockProvider;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.Protocol;

/**
 * Kiểm thử Pact phía tiêu dùng của SVC-13 với HRM qua REST (DV-13-11): bộ kết nối nhân sự và nguồn
 * chỉ số chuyên môn với hồ sơ mặc định chạy với đối tác giả lập dựng từ {@code
 * pacts/integration-gateway-hrm.json}.
 */
class HrmPactTest {

  static final PactMockProvider DOI_TAC = PactMockProvider.of("integration-gateway-hrm.json");

  @AfterAll
  static void stop() {
    DOI_TAC.close();
  }

  @BeforeEach
  void reset() {
    DOI_TAC.reset();
  }

  final ObjectMapper json = new ObjectMapper();
  final HrmHttp http =
      new HrmHttp(
          HttpClient.newHttpClient(),
          json,
          p -> "hrm/token".equals(p) ? Optional.of("ma-gia-lap") : Optional.empty());
  final Connector connector =
      new Connector(
          UUID.randomUUID(),
          UUID.randomUUID(),
          "HRM",
          Protocol.REST,
          DOI_TAC.baseUrl(),
          "hrm/token",
          Direction.VAO,
          null,
          true);

  @Test
  void keoNhanSuTheoHopDong() throws IOException {
    HrmProperties.Rest rest =
        new HrmProperties.Rest(null, null, null, null, null, null, null, null);
    RestHrmConnector source =
        new RestHrmConnector(
            "HRM", http, rest, Duration.ofMinutes(15), Duration.ofSeconds(5), (c, m) -> List.of());
    Page first = source.fetch(connector, source.initialCursor());
    JsonNode items = json.readTree(first.items().get(0).raw());
    assertThat(items).hasSize(1);
    assertThat(items.get(0).path("externalId").asText()).isEqualTo("NS-R1");
    assertThat(first.nextCursor()).isEqualTo("c2");
    assertThat(source.fetch(connector, first.nextCursor()).items()).isEmpty();
    DOI_TAC.verify(
        List.of("kéo trang nhân sự đầu tiên theo con trỏ", "kéo sau con trỏ cuối trả trang rỗng"));
  }

  @Test
  void docChiSoTheoHopDong() throws IOException {
    RestIndicatorSource source =
        new RestIndicatorSource(
            "HRM", http, new HrmProperties.Indicator(null, null, null), Duration.ofSeconds(5));
    IndicatorSource.Reading r = source.read(connector, "CS-1");
    assertThat(r.value()).isEqualByComparingTo(new BigDecimal("2.6"));
    assertThat(r.measuredAt()).isEqualTo(Instant.parse("2026-10-22T01:00:00Z"));
    DOI_TAC.verify(List.of("đọc giá trị chỉ số chuyên môn"));
  }
}
