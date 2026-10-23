package vn.viettel.okrkpi.integ.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.port.KtxhValues;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Số liệu nguồn của KR từ chỉ tiêu KT-XH: chỉ giá trị đã công bố (BR-16-05). */
class KtxhIndicatorSourceTest {

  @Test
  void chiTraGiaTriDaCongBo() throws IOException {
    UUID tenant = UUID.randomUUID();
    Connector c =
        new Connector(
            UUID.randomUUID(),
            tenant,
            "LGSP",
            Protocol.REST,
            "https://lgsp.invalid",
            "kv/x",
            Direction.VAO,
            null,
            true);
    KtxhValues values = mock(KtxhValues.class);
    KtxhIndicatorSource s =
        new KtxhIndicatorSource("LGSP", values, TransactionOperations.withoutTransaction());
    assertThat(s.connectorCode()).isEqualTo("LGSP");
    Instant at = Instant.parse("2026-07-15T02:00:00Z");
    when(values.latestPublished(TenantId.of(tenant), c.id(), "GRDP"))
        .thenReturn(
            Optional.of(
                new KtxhValues.Value(
                    "GRDP", "2026-Q2", "T01", new BigDecimal("7.2"), "%", at, at)));
    assertThat(s.read(c, "GRDP")).isEqualTo(new IndicatorSource.Reading(new BigDecimal("7.2"), at));
    when(values.latestPublished(TenantId.of(tenant), c.id(), "CPI")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> s.read(c, "CPI")).isInstanceOf(IOException.class);
  }
}
