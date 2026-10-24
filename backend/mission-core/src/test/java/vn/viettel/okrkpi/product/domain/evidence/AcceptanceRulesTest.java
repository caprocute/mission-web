package vn.viettel.okrkpi.product.domain.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** CORE-03-07: điều kiện quyết định nghiệm thu (BR-07-07, BR-07-08, MSG-07-09, MSG-07-10). */
class AcceptanceRulesTest {

  private static final List<CriterionResult> ONE = List.of(new CriterionResult("TC-1", true, null));

  private static String accept(
      String quality,
      List<CriterionResult> criteria,
      Set<String> required,
      boolean revised,
      String cause,
      Boolean objective,
      String comment) {
    try {
      AcceptanceRules.checkAccept(
          quality == null ? null : new BigDecimal(quality),
          criteria,
          required,
          revised,
          cause,
          objective,
          comment);
      return null;
    } catch (EvidenceRuleViolation e) {
      return e.code();
    }
  }

  private static String giveBack(List<CriterionResult> criteria, String comment) {
    try {
      AcceptanceRules.checkReturn(criteria, comment);
      return null;
    } catch (EvidenceRuleViolation e) {
      return e.code();
    }
  }

  @Test
  void xacNhanHopLe() {
    assertThat(accept("1", ONE, Set.of(), false, null, null, null)).isNull();
    assertThat(accept("1.00", ONE, Set.of("TC-1"), false, null, null, "")).isNull();
    assertThat(accept("0.25", ONE, Set.of(), true, "THE_THUC", false, "Thiếu chữ ký")).isNull();
  }

  @Test
  void mucChatLuongNgoaiNamMuc() {
    assertThat(accept("0.6", ONE, Set.of(), false, null, null, "x"))
        .isEqualTo(EvidenceRuleViolation.VALIDATION);
    assertThat(accept(null, ONE, Set.of(), false, null, null, "x"))
        .isEqualTo(EvidenceRuleViolation.VALIDATION);
  }

  @Test
  void tieuChuanChuaDanhGiaHet() {
    // Không có tiêu chuẩn bắt buộc: danh sách rỗng hợp lệ; còn tiêu chuẩn bắt buộc thì phải đủ
    assertThat(accept("1", List.of(), Set.of(), false, null, null, null)).isNull();
    assertThat(accept("1", List.of(), Set.of("TC-1"), false, null, null, null))
        .isEqualTo(EvidenceRuleViolation.CRITERIA_INCOMPLETE);
    assertThat(accept("1", null, Set.of(), false, null, null, null))
        .isEqualTo(EvidenceRuleViolation.CRITERIA_INCOMPLETE);
    assertThat(accept("1", ONE, Set.of("TC-1", "TC-2"), false, null, null, null))
        .isEqualTo(EvidenceRuleViolation.CRITERIA_INCOMPLETE);
    List<CriterionResult> dup =
        List.of(new CriterionResult("TC-1", true, null), new CriterionResult(" TC-1", false, null));
    assertThat(accept("1", dup, Set.of(), false, null, null, null))
        .isEqualTo(EvidenceRuleViolation.CRITERIA_INCOMPLETE);
    assertThat(
            accept(
                "1",
                Arrays.asList(new CriterionResult(" ", true, null)),
                Set.of(),
                false,
                null,
                null,
                null))
        .isEqualTo(EvidenceRuleViolation.CRITERIA_INCOMPLETE);
    assertThat(
            accept(
                "1",
                List.of(new CriterionResult("TC-1", true, "x".repeat(501))),
                Set.of(),
                false,
                null,
                null,
                null))
        .isEqualTo(EvidenceRuleViolation.VALIDATION);
  }

  @Test
  void nhanXetVaNguyenNhanChinhSua() {
    assertThat(accept("0.5", ONE, Set.of(), false, null, null, " "))
        .isEqualTo(EvidenceRuleViolation.COMMENT_REQUIRED);
    assertThat(accept("1", ONE, Set.of(), false, null, null, "x".repeat(2001)))
        .isEqualTo(EvidenceRuleViolation.VALIDATION);
    assertThat(accept("1", ONE, Set.of(), true, null, false, null))
        .isEqualTo(EvidenceRuleViolation.REVISION_CAUSE_REQUIRED);
    assertThat(accept("1", ONE, Set.of(), true, "THE_THUC", null, null))
        .isEqualTo(EvidenceRuleViolation.REVISION_CAUSE_REQUIRED);
  }

  @Test
  void boSungVaTuChoiCanNhanXet() {
    assertThat(giveBack(List.of(), "Thiếu số liệu")).isNull();
    assertThat(giveBack(List.of(), "  ")).isEqualTo(EvidenceRuleViolation.COMMENT_REQUIRED);
    assertThat(giveBack(null, "x")).isEqualTo(EvidenceRuleViolation.CRITERIA_INCOMPLETE);
  }
}
