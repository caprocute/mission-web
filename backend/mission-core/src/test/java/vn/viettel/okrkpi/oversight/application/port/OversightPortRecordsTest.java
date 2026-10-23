package vn.viettel.okrkpi.oversight.application.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử các bản ghi con trỏ, bộ lọc và mặc định của cổng MOD-07. */
class OversightPortRecordsTest {

  private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");

  @Test
  void conTroVaBoLocGiuDungGiaTri() {
    UUID id = UUID.randomUUID();
    assertThat(new AnomalyAlertRepository.After(2, NOW, id).rank()).isEqualTo(2);
    assertThat(new ReopenRequestRepository.After(NOW, id).createdAt()).isEqualTo(NOW);
    assertThat(
            new ComplaintHandlingRepository.DeadlineAfter(LocalDate.of(2026, 11, 6), id).deadline())
        .isEqualTo(LocalDate.of(2026, 11, 6));
    assertThat(new ComplaintHandlingRepository.ResponseAfter(NOW, id).id()).isEqualTo(id);
    ReopenRequestRepository.Filter f =
        new ReopenRequestRepository.Filter(null, List.of(), false, id, List.of());
    assertThat(f.awaitingFor()).isNull();
    assertThat(f.viewer()).isEqualTo(id);
  }

  @Test
  void doiTuongCanhBaoKhongCoNguoiThiRong() {
    assertThat(new AlertSubjects.Subject(UUID.randomUUID(), null, "Đơn vị").people()).isEmpty();
    UUID p = UUID.randomUUID();
    assertThat(new AlertSubjects.Subject(null, Set.of(p), null).people()).containsExactly(p);
  }

  @Test
  void thongBaoCanhBaoMacDinhKhongLamGi() {
    int[] calls = {0};
    AlertNotices n = (tenant, recipient, count, batch) -> calls[0]++;
    TenantId t = TenantId.of(UUID.randomUUID());
    n.newHighAlerts(t, UUID.randomUUID(), 1, UUID.randomUUID());
    n.assigned(t, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    n.validated(t, List.of(), UUID.randomUUID(), 0, UUID.randomUUID());
    assertThat(calls[0]).isEqualTo(1);
  }
}
