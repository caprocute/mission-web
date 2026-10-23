package vn.viettel.okrkpi.oversight.application.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository.Adjustment;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScope;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Hàm mặc định và bản ghi của các cổng oversight (CORE-07-10, CORE-07-11, CORE-07-07). */
class OversightPortsTest {

  private static final TenantId T = TenantId.of(UUID.randomUUID());

  @Test
  void thongBaoMacDinhKhongLamGi() {
    UUID recipient = UUID.randomUUID();
    int[] high = new int[1];
    AlertNotices notices = (tenant, to, count, batch) -> high[0] += count;
    notices.newHighAlerts(T, recipient, 2, UUID.randomUUID());
    notices.assigned(T, recipient, UUID.randomUUID(), UUID.randomUUID());
    notices.validated(T, List.of(recipient), UUID.randomUUID(), 3, UUID.randomUUID());
    assertThat(high[0]).isEqualTo(2);
  }

  @Test
  void doiTuongCanhBaoChepTapNguoi() {
    UUID unit = UUID.randomUUID();
    Set<UUID> people = new HashSet<>(Set.of(UUID.randomUUID()));
    AlertSubjects.Subject s = new AlertSubjects.Subject(unit, people, "Phòng A");
    people.add(UUID.randomUUID());
    assertThat(s.people()).hasSize(1);
    assertThat(s.unitId()).isEqualTo(unit);
    assertThat(s.name()).isEqualTo("Phòng A");
    assertThat(new AlertSubjects.Subject(null, null, null).people()).isEmpty();
  }

  @Test
  void banGhiKhoCanhBao() {
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    var f =
        new AnomalyAlertRepository.Filter(id, null, List.of("S1"), List.of("CAO"), List.of("MOI"));
    assertThat(f.periodId()).isEqualTo(id);
    assertThat(f.signals()).containsExactly("S1");
    var after = new AnomalyAlertRepository.After(0, now, id);
    assertThat(after.rank()).isZero();
    var a =
        new AnomalyAlertRepository.Action(id, "GIAO_KIEM_TRA", id, id, null, "ghi chú", true, now);
    assertThat(a.active()).isTrue();
    assertThat(a.actedAt()).isEqualTo(now);
    var tally = new AnomalyAlertRepository.Tally(null, true, false, true);
    assertThat(tally.valid()).isTrue();
    assertThat(tally.violation()).isTrue();
    AlertMetrics metrics = (signal, outcome) -> assertThat(outcome).isEqualTo("KHONG_VI_PHAM");
    metrics.decided("S1", "KHONG_VI_PHAM");
  }

  @Test
  void moPhongMoLaiChepDanhSach() {
    var adj = new Adjustment("k", "1", "2", "lý do");
    var req =
        new ReopenImpactSimulator.Request(
            UUID.randomUUID(),
            ReopenScope.HO_SO,
            List.of(),
            List.of(UUID.randomUUID()),
            List.of(adj));
    assertThat(req.adjustments()).containsExactly(adj);
    assertThatThrownBy(() -> req.profileIds().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    var row = new ReopenImpactSimulator.Row(UUID.randomUUID(), "diem", "80", "85");
    var impact = new ReopenImpactSimulator.Impact("v1", List.of(row), 1);
    assertThat(impact.rows()).containsExactly(row);
    assertThat(impact.formsToRegenerate()).isEqualTo(1);
  }
}
