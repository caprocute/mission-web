package vn.viettel.okrkpi.product.domain.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** CORE-03-06: điều kiện mở nghiệm thu (BR-07-06, BR-07-04, MSG-07-13). */
class ReviewRulesTest {

  private static final UUID REVIEWER = UUID.randomUUID();
  private static final UUID SUBMITTER = UUID.randomUUID();

  private static byte[] sha(int b) {
    byte[] s = new byte[32];
    Arrays.fill(s, (byte) b);
    return s;
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
  void nguoiNghiemThuDocLap() {
    assertThatCode(
            () ->
                ReviewRules.requireIndependentReviewer(
                    REVIEWER, REVIEWER, SUBMITTER, SUBMITTER, false))
        .doesNotThrowAnyException();
    assertThat(
            code(
                () ->
                    ReviewRules.requireIndependentReviewer(
                        SUBMITTER, REVIEWER, SUBMITTER, SUBMITTER, false)))
        .isEqualTo(EvidenceRuleViolation.SELF_REVIEW);
    assertThat(
            code(
                () ->
                    ReviewRules.requireIndependentReviewer(
                        REVIEWER, REVIEWER, REVIEWER, SUBMITTER, false)))
        .isEqualTo(EvidenceRuleViolation.SELF_REVIEW);
    assertThat(
            code(
                () ->
                    ReviewRules.requireIndependentReviewer(
                        REVIEWER, REVIEWER, SUBMITTER, REVIEWER, false)))
        .isEqualTo(EvidenceRuleViolation.SELF_REVIEW);
    assertThat(
            code(
                () ->
                    ReviewRules.requireIndependentReviewer(
                        REVIEWER, REVIEWER, SUBMITTER, SUBMITTER, true)))
        .isEqualTo(EvidenceRuleViolation.SELF_REVIEW);
    assertThat(
            code(
                () ->
                    ReviewRules.requireIndependentReviewer(
                        REVIEWER, null, SUBMITTER, SUBMITTER, false)))
        .isEqualTo(EvidenceRuleViolation.SELF_REVIEW);
  }

  @ParameterizedTest
  @EnumSource(EvidenceStatus.class)
  void chiMoKhiDaNopHoacDangNghiemThu(EvidenceStatus s) {
    boolean ok = s == EvidenceStatus.DA_NOP || s == EvidenceStatus.DANG_NGHIEM_THU;
    assertThat(code(() -> ReviewRules.requireReviewable(s)))
        .isEqualTo(ok ? null : EvidenceRuleViolation.INVALID_STATE);
  }

  @Test
  void toanVenVaSach() {
    EvidenceFile ok =
        new EvidenceFile(UUID.randomUUID(), 1, 1, sha(1), 1, sha(1), "SACH", null, null);
    EvidenceFile newer =
        new EvidenceFile(UUID.randomUUID(), 2, 1, sha(2), 2, sha(3), "CHO_QUET", null, null);
    assertThat(ReviewRules.tampered(List.of(ok))).isEmpty();
    assertThat(ReviewRules.tampered(List.of(ok, newer))).containsExactly(newer);
    assertThatCode(() -> ReviewRules.requireClean(List.of(ok))).doesNotThrowAnyException();
    EvidenceFile infected =
        new EvidenceFile(UUID.randomUUID(), 2, 1, sha(2), 1, sha(2), "NHIEM", null, null);
    assertThat(code(() -> ReviewRules.requireClean(List.of(ok, infected))))
        .isEqualTo(EvidenceRuleViolation.NOT_CLEAN);
  }
}
