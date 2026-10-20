package vn.viettel.okrkpi.org.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;

class SmallHelpersTest {

  @Test
  void boDauTiengViet() {
    assertThat(TextFold.fold("Sở Tài chính Đắk Lắk")).isEqualTo("so tai chinh dak lak");
    assertThat(TextFold.fold("ĐẢNG ỦY")).isEqualTo("dang uy");
  }

  @Test
  void conTroKhuHoiVaTuChoiSai() {
    String c = Cursors.encode("Sở A", "2026-01-01", UUID.randomUUID().toString());
    assertThat(Cursors.decode(c, 3)).hasSize(3).first().isEqualTo("Sở A");
    assertThatThrownBy(() -> Cursors.decode(c, 2)).isInstanceOf(OrgRuleViolation.class);
    assertThatThrownBy(() -> Cursors.decode("@@@", 2)).isInstanceOf(OrgRuleViolation.class);
  }

  @Test
  void outboxChiChoPhepC0C1VaGiuGiaTriNull() {
    UUID tenant = UUID.randomUUID();
    UUID agg = UUID.randomUUID();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", null);
    OutboxEvent e = event(tenant, agg, "C1", payload);
    assertThat(e.eventKey()).isEqualTo(tenant + ":" + agg);
    assertThat(e.payload()).containsEntry("name", null);
    assertThatThrownBy(() -> event(tenant, agg, "C2", payload))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static OutboxEvent event(UUID tenant, UUID agg, String cls, Map<String, Object> payload) {
    OrgEvents t = OrgEvents.UNIT_CHANGED;
    return new OutboxEvent(UUID.randomUUID(), t.topic(), t.eventType(), t.aggregateType(), agg, 3, tenant,
        UUID.randomUUID(), java.time.Instant.now(), null, cls, payload);
  }

  @Test
  void truyVanThoiGianMacDinhLaHieuBietHienTai() {
    assertThat(new TemporalQuery(java.time.LocalDate.now(), null).currentKnowledge()).isTrue();
  }
}
