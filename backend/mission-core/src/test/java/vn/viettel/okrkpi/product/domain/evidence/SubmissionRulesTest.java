package vn.viettel.okrkpi.product.domain.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** CORE-03-03: điều kiện nộp nhiều tệp (BR-07-02, BR-07-03, BR-07-04) và thứ tự thông báo. */
class SubmissionRulesTest {

  private static byte[] sha(int b) {
    byte[] s = new byte[32];
    Arrays.fill(s, (byte) b);
    return s;
  }

  private static EvidenceFile clean(int b) {
    return new EvidenceFile(UUID.randomUUID(), 1, 1, sha(b), 1, sha(b), "SACH", null, null);
  }

  private static String code(Runnable r) {
    try {
      r.run();
      return null;
    } catch (EvidenceRuleViolation e) {
      return e.code();
    }
  }

  @Test
  void hopLeKhiDuDieuKien() {
    assertThatCode(() -> SubmissionRules.check(true, List.of(clean(1), clean(2))))
        .doesNotThrowAnyException();
  }

  @Test
  void thuTuKiem() {
    EvidenceFile changed =
        new EvidenceFile(UUID.randomUUID(), 1, 1, sha(1), 2, sha(9), "NHIEM", null, null);
    assertThat(code(() -> SubmissionRules.check(false, List.of(changed))))
        .isEqualTo(EvidenceRuleViolation.ATTESTATION_REQUIRED);
    assertThat(code(() -> SubmissionRules.check(true, List.of())))
        .isEqualTo(EvidenceRuleViolation.NO_OBJECT);
    assertThat(code(() -> SubmissionRules.check(true, List.of(clean(1), changed))))
        .isEqualTo(EvidenceRuleViolation.HASH_MISMATCH);
    EvidenceFile infected =
        new EvidenceFile(UUID.randomUUID(), 2, 1, sha(3), 1, sha(3), "NHIEM", null, null);
    assertThat(code(() -> SubmissionRules.check(true, List.of(clean(1), infected))))
        .isEqualTo(EvidenceRuleViolation.NOT_CLEAN);
    EvidenceFile pending =
        new EvidenceFile(UUID.randomUUID(), 2, 1, sha(3), 1, sha(3), "CHO_QUET", null, null);
    assertThat(code(() -> SubmissionRules.check(true, List.of(pending))))
        .isEqualTo(EvidenceRuleViolation.NOT_CLEAN);
  }

  @Test
  void maBamKhongKhopKhiChuaLuuHoacLechPhienBan() {
    EvidenceFile notStored =
        new EvidenceFile(UUID.randomUUID(), 1, 1, sha(1), null, null, "SACH", null, null);
    assertThat(notStored.hashMatches()).isFalse();
    EvidenceFile sameHashNewVersion =
        new EvidenceFile(UUID.randomUUID(), 1, 1, sha(1), 2, sha(1), "SACH", null, null);
    assertThat(sameHashNewVersion.hashMatches()).isFalse();
    assertThat(clean(4).hashMatches()).isTrue();
  }

  @Test
  void tepTrungCanGiaiTrinhTuHaiMuoiDenMotNghinKyTu() {
    UUID other = UUID.randomUUID();
    EvidenceFile shortReason =
        new EvidenceFile(UUID.randomUUID(), 1, 1, sha(1), 1, sha(1), "SACH", other, "  ngắn  ");
    assertThat(code(() -> SubmissionRules.check(true, List.of(shortReason))))
        .isEqualTo(EvidenceRuleViolation.REUSE_JUSTIFICATION_REQUIRED);
    String twenty = "Dùng chung báo cáo q";
    assertThat(twenty).hasSize(20);
    EvidenceFile ok =
        new EvidenceFile(UUID.randomUUID(), 1, 1, sha(1), 1, sha(1), "SACH", other, twenty);
    assertThatCode(() -> SubmissionRules.check(true, List.of(ok))).doesNotThrowAnyException();
    assertThat(EvidenceFile.justificationValid("x".repeat(1000))).isTrue();
    assertThat(EvidenceFile.justificationValid("x".repeat(1001))).isFalse();
    assertThat(EvidenceFile.justificationValid(null)).isFalse();
  }

  @Test
  void ketQuaQuetGop() {
    assertThat(SubmissionRules.aggregateScan(List.of())).isEqualTo("CHO_QUET");
    assertThat(SubmissionRules.aggregateScan(List.of("SACH", "SACH"))).isEqualTo("SACH");
    assertThat(SubmissionRules.aggregateScan(List.of("SACH", "CHO_QUET"))).isEqualTo("CHO_QUET");
    assertThat(SubmissionRules.aggregateScan(List.of("LOI", "CHO_QUET"))).isEqualTo("LOI");
    assertThat(SubmissionRules.aggregateScan(List.of("LOI", "NHIEM", "SACH"))).isEqualTo("NHIEM");
  }

  @ParameterizedTest
  @EnumSource(EvidenceStatus.class)
  void phienBanKeTiepChiKhiPhienBanMoiNhatDaKetThuc(EvidenceStatus latest) {
    boolean open =
        latest == EvidenceStatus.NHAP
            || latest == EvidenceStatus.DA_NOP
            || latest == EvidenceStatus.DANG_NGHIEM_THU;
    if (open) {
      assertThatThrownBy(() -> SubmissionRules.requireCanOpenNext(latest))
          .isInstanceOf(EvidenceRuleViolation.class)
          .extracting("code")
          .isEqualTo(EvidenceRuleViolation.OPEN_VERSION_EXISTS);
    } else {
      assertThatCode(() -> SubmissionRules.requireCanOpenNext(latest)).doesNotThrowAnyException();
    }
    assertThatCode(() -> SubmissionRules.requireCanOpenNext(null)).doesNotThrowAnyException();
  }
}
