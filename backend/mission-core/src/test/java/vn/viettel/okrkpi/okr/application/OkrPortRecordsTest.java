package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalStarter.ApprovalStarted;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegateRepository.Delegation;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegateRepository.KeyResultCode;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinExtension.Submitted;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinReminderRepository.DueKeyResult;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinRow;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.CheckinTarget;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinRepository.NewCheckin;
import vn.viettel.okrkpi.okr.application.port.OkrMilestoneRepository.MilestoneDraft;
import vn.viettel.okrkpi.okr.application.port.OkrReviewRepository.ReviewRow;
import vn.viettel.okrkpi.okr.application.port.OkrReviewRepository.ReviewTarget;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.KeyResultData;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.ObjectiveData;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.ObjectiveHead;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.ObjectiveText;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Hành vi nhỏ của các bản ghi trong cổng OKR: chủ sở hữu KR, hiệu lực uỷ quyền, bằng nhau. */
class OkrPortRecordsTest {

  static final UUID A = UUID.randomUUID();
  static final UUID B = UUID.randomUUID();

  static CheckinTarget target(UUID person, UUID creator) {
    return new CheckinTarget(
        A,
        A,
        1,
        "KR",
        "SO_LUONG",
        "TANG",
        null,
        BigDecimal.ONE,
        null,
        BigDecimal.ZERO,
        null,
        "BINH_THUONG",
        0,
        A,
        "O1",
        "DA_DUYET",
        BigDecimal.ZERO,
        A,
        person,
        creator);
  }

  @Test
  void chuSoHuuKrLaNguoiCuaMucTieuCaNhanHoacNguoiLap() {
    assertThat(target(A, B).owner()).isEqualTo(A);
    assertThat(target(null, B).owner()).isEqualTo(B);
  }

  @Test
  void uyQuyenHieuLucDenHetNgayHan() {
    LocalDate d = LocalDate.of(2027, 2, 10);
    assertThat(new Delegation(A, A, B, null, null, A, Instant.EPOCH).validOn(d)).isTrue();
    assertThat(new Delegation(A, A, B, d, null, A, Instant.EPOCH).validOn(d)).isTrue();
    assertThat(new Delegation(A, A, B, d.minusDays(1), null, A, Instant.EPOCH).validOn(d))
        .isFalse();
  }

  @Test
  void banGhiSoSanhTheoGiaTri() {
    List<Object> records =
        List.of(
            new CheckinRow(
                A,
                A,
                A,
                Instant.EPOCH,
                BigDecimal.ONE,
                "CAO",
                null,
                null,
                "WEB",
                "DA_GUI",
                null,
                null,
                null,
                null,
                null,
                A,
                "k"),
            new NewCheckin(
                A,
                A,
                1,
                A,
                BigDecimal.ONE,
                "CAO",
                null,
                null,
                "WEB",
                "NHAP",
                "k",
                null,
                null,
                null),
            new DueKeyResult(A, "KR", "TUAN", A, A, A, null, Instant.EPOCH, null, 0),
            new ApprovalStarted("pi", A, null),
            new KeyResultData(
                null, "t", null, "u", null, null, null, null, null, null, null, null, null, null,
                null, null, null),
            new ObjectiveData("t", null, 1, null, null, null),
            new ObjectiveText("t", null, 1),
            new ObjectiveHead(A, A, 1, "TAP_THE", A, null, "O1", null, "NHAP", 0),
            new MilestoneDraft(null, 1, LocalDate.EPOCH, null),
            new KeyResultCode(A, "KR"),
            new ReviewRow(A, 1, A, "DUYET", null, Instant.EPOCH),
            new ReviewTarget(A, A, 1, A, null, "O1", "t", 1, "CHO_DUYET", 1, A, null, 0),
            new Submitted(
                TenantId.of(A), A, target(A, B), null, BigDecimal.ONE, BigDecimal.ONE, false),
            new OkrComposeService.Owner(A, "TAP_THE", A, null),
            new OkrComposeService.LinkDraft("BASIS", A, "OBJECTIVE", B, "CAN_CU", null),
            new OkrObjectiveRevisionApproved(TenantId.of(A), A, B, 1, A));
    for (Object r : records) {
      assertThat(r).isEqualTo(r).hasSameHashCodeAs(r);
      assertThat(r.toString()).isNotBlank();
    }
  }
}
