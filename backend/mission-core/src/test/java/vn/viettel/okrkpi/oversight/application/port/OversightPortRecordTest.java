package vn.viettel.okrkpi.oversight.application.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Bản ghi con trỏ và bộ lọc của cổng khiếu nại, mở lại kỳ (CORE-07-02, CORE-07-03, CORE-07-11). */
class OversightPortRecordTest {

  @Test
  void conTroVaBoLocGiuDungGiaTri() {
    UUID id = UUID.randomUUID();
    Instant luc = Instant.parse("2026-10-23T01:00:00Z");
    LocalDate han = LocalDate.parse("2026-11-01");
    assertThat(new ComplaintRepository.HandlerAfter(han, id).deadline()).isEqualTo(han);
    assertThat(new ComplaintRepository.After(luc, id).id()).isEqualTo(id);
    assertThat(new ReopenRequestRepository.After(luc, id).createdAt()).isEqualTo(luc);

    UUID ky = UUID.randomUUID();
    var loc = new ReopenRequestRepository.Filter(ky, List.of(), false, id, List.of());
    assertThat(loc.awaitingFor()).isNull();
    assertThat(loc.periodId()).isEqualTo(ky);
    assertThat(loc.everything()).isFalse();
  }

  @Test
  void doiTuongCanhBaoKhongCoNguoiThiRong() {
    UUID donVi = UUID.randomUUID();
    UUID nguoi = UUID.randomUUID();
    assertThat(new AlertSubjects.Subject(donVi, null, "Phòng A").people()).isEmpty();
    assertThat(new AlertSubjects.Subject(donVi, Set.of(nguoi), "Phòng A").people())
        .containsExactly(nguoi);
  }
}
