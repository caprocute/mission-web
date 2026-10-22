package vn.viettel.okrkpi.product.domain.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.viettel.okrkpi.product.domain.evidence.EvidenceFixtures.version;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Tổng hợp sản phẩm (BR-07-05) và quy tắc liên động (BR-07-10). */
class DeliverableRollupTest {

  private static final Random RANDOM = new Random(20271);

  @Test
  void trangThaiTongHop() {
    BigDecimal two = BigDecimal.valueOf(2);
    assertThat(DeliverableRollup.of(two, List.of()).status()).isEqualTo(DeliverableStatus.CHUA_CO);
    assertThat(DeliverableRollup.of(two, List.of(version(EvidenceStatus.DA_NOP))).status())
        .isEqualTo(DeliverableStatus.DANG_THUC_HIEN);
    DeliverableRollup motPhan =
        DeliverableRollup.of(
            two, List.of(version(EvidenceStatus.DA_XAC_NHAN), version(EvidenceStatus.TU_CHOI)));
    assertThat(motPhan.status()).isEqualTo(DeliverableStatus.DA_XAC_NHAN_MOT_PHAN);
    assertThat(motPhan.quantityAccepted()).isEqualByComparingTo("1");
    assertThat(motPhan.fullySubmitted()).isFalse();
    DeliverableRollup du =
        DeliverableRollup.of(
            two, List.of(version(EvidenceStatus.DA_XAC_NHAN), version(EvidenceStatus.DA_XAC_NHAN)));
    assertThat(du.status()).isEqualTo(DeliverableStatus.DA_XAC_NHAN);
    assertThat(du.fullyAccepted()).isTrue();
  }

  /**
   * Thuộc tính: số lượng xác nhận luôn bằng số phiên bản được tính điểm, trong [0, số minh chứng].
   */
  @RepeatedTest(200)
  void soLuongXacNhanChiDemPhienBanDaXacNhan() {
    int n = RANDOM.nextInt(8);
    List<EvidenceVersion> versions = new ArrayList<>();
    long expected = 0;
    long live = 0;
    for (int i = 0; i < n; i++) {
      EvidenceStatus s = EvidenceStatus.values()[RANDOM.nextInt(EvidenceStatus.values().length)];
      versions.add(version(s));
      expected += s == EvidenceStatus.DA_XAC_NHAN ? 1 : 0;
      live += s.isSubmittedAndLive() ? 1 : 0;
    }
    BigDecimal planned = BigDecimal.valueOf(1 + RANDOM.nextInt(5));
    DeliverableRollup r = DeliverableRollup.of(planned, versions);
    assertThat(r.quantityAccepted()).isEqualByComparingTo(BigDecimal.valueOf(expected));
    assertThat(r.submittedCount()).isEqualTo(live);
    assertThat(r.quantityAccepted().longValue()).isBetween(0L, (long) n);
    assertThat(r.fullyAccepted())
        .isEqualTo(n > 0 && BigDecimal.valueOf(expected).compareTo(planned) >= 0);
  }

  @Test
  void lienDongNhiemVu() {
    BigDecimal one = BigDecimal.ONE;
    DeliverableRollup daNop = DeliverableRollup.of(one, List.of(version(EvidenceStatus.DA_NOP)));
    DeliverableRollup chuaNop = DeliverableRollup.of(one, List.of(version(EvidenceStatus.NHAP)));
    DeliverableRollup xacNhan =
        DeliverableRollup.of(one, List.of(version(EvidenceStatus.DA_XAC_NHAN)));

    assertThat(TaskInterlock.after(EvidenceStatus.DA_NOP, List.of(daNop, xacNhan)))
        .contains(TaskInterlock.TaskRequest.SUBMIT);
    assertThat(TaskInterlock.after(EvidenceStatus.DA_NOP, List.of(daNop, chuaNop))).isEmpty();
    assertThat(TaskInterlock.after(EvidenceStatus.DA_XAC_NHAN, List.of(xacNhan, daNop))).isEmpty();
    assertThat(TaskInterlock.after(EvidenceStatus.DA_XAC_NHAN, List.of(xacNhan)))
        .contains(TaskInterlock.TaskRequest.APPROVE);
    assertThat(TaskInterlock.after(EvidenceStatus.TU_CHOI, List.of(xacNhan)))
        .contains(TaskInterlock.TaskRequest.REWORK);
    assertThat(TaskInterlock.after(EvidenceStatus.YEU_CAU_BO_SUNG, List.of()))
        .contains(TaskInterlock.TaskRequest.REWORK);
    assertThat(TaskInterlock.after(EvidenceStatus.DANG_NGHIEM_THU, List.of(daNop)))
        .isEqualTo(Optional.empty());
    assertThat(TaskInterlock.after(EvidenceStatus.DA_NOP, List.of())).isEmpty();
  }
}
