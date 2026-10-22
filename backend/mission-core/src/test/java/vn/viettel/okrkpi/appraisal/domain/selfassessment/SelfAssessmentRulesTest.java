package vn.viettel.okrkpi.appraisal.domain.selfassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;

/** BR-09-05 quy đổi tiêu chí chung, kiểm nháp và kiểm nộp của phiếu tự đánh giá, băm nội dung. */
class SelfAssessmentRulesTest {

  private static final String REASON = "Đơn vị ghi nhận giải pháp số hoá báo cáo tuần của tôi.";
  private final UUID evidence = UUID.randomUUID();

  @Test
  void quyDoiTyLeTieuChiChung() {
    assertThat(CriteriaLevel.ratio(0, 0)).isEqualByComparingTo("0");
    assertThat(CriteriaLevel.ratio(2, 3)).isEqualByComparingTo("0.6666");
    assertThat(CriteriaLevel.of(CriteriaLevel.ratio(10, 10)))
        .isEqualTo(CriteriaLevel.THUC_HIEN_TOT);
    assertThat(CriteriaLevel.of(new BigDecimal("0.9000"))).isEqualTo(CriteriaLevel.DAM_BAO);
    assertThat(CriteriaLevel.of(new BigDecimal("0.8999"))).isEqualTo(CriteriaLevel.CO_BAN_DAM_BAO);
    assertThat(CriteriaLevel.of(new BigDecimal("0.6000"))).isEqualTo(CriteriaLevel.CO_BAN_DAM_BAO);
    assertThat(CriteriaLevel.of(new BigDecimal("0.5999"))).isEqualTo(CriteriaLevel.KHONG_DAM_BAO);
  }

  @Test
  void nhapKiemMaTieuChiVaDoDai() {
    assertThat(SelfAssessmentRules.checkDraft(SelfAssessmentForm.empty())).isEmpty();
    List<SelfAssessmentForm.Criterion> many = new ArrayList<>();
    for (int i = 0; i < 31; i++) {
      many.add(new SelfAssessmentForm.Criterion("TCC-" + i, true));
    }
    assertThat(SelfAssessmentRules.checkDraft(form(many, null, null, null, "a")))
        .extracting(SelfAssessmentIssue::field)
        .contains("criteria");
    assertThat(
            SelfAssessmentRules.checkDraft(
                form(
                    List.of(
                        new SelfAssessmentForm.Criterion("TCC-01", true),
                        new SelfAssessmentForm.Criterion("TCC-01", false)),
                    null,
                    null,
                    null,
                    null)))
        .singleElement()
        .extracting(SelfAssessmentIssue::kind)
        .isEqualTo(SelfAssessmentIssue.Kind.VALIDATION);
    assertThat(SelfAssessmentRules.checkDraft(form(List.of(), null, null, null, "x".repeat(2001))))
        .extracting(SelfAssessmentIssue::field)
        .containsExactly("content");
    assertThatThrownBy(() -> new SelfAssessmentForm.Criterion(null, true))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nopThieuTruongBatBuoc() {
    assertThat(SelfAssessmentRules.checkSubmit(SelfAssessmentForm.empty(), null, Set.of()))
        .extracting(SelfAssessmentIssue::field)
        .containsExactly("criteria", "selfRatingCode", "content");
  }

  @Test
  void khacMucThamKhaoPhaiGiaiTrinh() {
    SelfAssessmentForm noReason = form(criteria(), RatingLevel.HTNV, null, null, "Nội dung");
    assertThat(SelfAssessmentRules.differs(noReason, RatingLevel.HTTNV)).isTrue();
    assertThat(SelfAssessmentRules.checkSubmit(noReason, RatingLevel.HTTNV, Set.of()))
        .singleElement()
        .satisfies(
            i -> {
              assertThat(i.kind()).isEqualTo(SelfAssessmentIssue.Kind.REASON);
              assertThat(i.rule()).isEqualTo("BR-09-12");
            });
    SelfAssessmentForm shortReason = form(criteria(), RatingLevel.HTNV, null, "ngắn", "Nội dung");
    assertThat(SelfAssessmentRules.checkSubmit(shortReason, RatingLevel.HTTNV, Set.of()))
        .hasSize(1);
    SelfAssessmentForm ok = form(criteria(), RatingLevel.HTNV, null, REASON, "Nội dung");
    assertThat(SelfAssessmentRules.checkSubmit(ok, RatingLevel.HTTNV, Set.of())).isEmpty();
    SelfAssessmentForm same = form(criteria(), RatingLevel.HTTNV, null, null, "Nội dung");
    assertThat(SelfAssessmentRules.differs(same, RatingLevel.HTTNV)).isFalse();
    assertThat(SelfAssessmentRules.checkSubmit(same, null, Set.of())).isEmpty();
  }

  @Test
  void xuatSacCanDotPhaVaMinhChungDaXacNhan() {
    SelfAssessmentForm f =
        new SelfAssessmentForm(
            criteria(),
            RatingLevel.HTXSNV,
            "Số hoá báo cáo tuần",
            List.of(evidence),
            REASON,
            "Nội dung",
            null,
            null,
            null,
            null);
    assertThat(SelfAssessmentRules.checkSubmit(f, RatingLevel.HTTNV, Set.of()))
        .singleElement()
        .extracting(SelfAssessmentIssue::kind)
        .isEqualTo(SelfAssessmentIssue.Kind.BREAKTHROUGH);
    assertThat(SelfAssessmentRules.checkSubmit(f, RatingLevel.HTTNV, Set.of(evidence))).isEmpty();
  }

  @Test
  void bamNoiDungOnDinhTheoThuTuVaDoiKhiSua() {
    UUID profile = UUID.randomUUID();
    SelfAssessmentForm a =
        new SelfAssessmentForm(
            List.of(
                new SelfAssessmentForm.Criterion("TCC-02", false),
                new SelfAssessmentForm.Criterion("TCC-01", true)),
            RatingLevel.HTTNV,
            null,
            null,
            null,
            "Nội dung",
            "Ưu",
            null,
            null,
            List.of(Map.of("taskId", UUID.randomUUID().toString())));
    SelfAssessmentForm b =
        new SelfAssessmentForm(
            List.of(
                new SelfAssessmentForm.Criterion("TCC-01", true),
                new SelfAssessmentForm.Criterion("TCC-02", false)),
            RatingLevel.HTTNV,
            null,
            null,
            null,
            "Nội dung",
            "Ưu",
            null,
            null,
            null);
    assertThat(ContentHash.of(profile, 1, a)).hasSize(32).isEqualTo(ContentHash.of(profile, 1, b));
    assertThat(ContentHash.of(profile, 2, a)).isNotEqualTo(ContentHash.of(profile, 1, a));
    SelfAssessmentForm c =
        new SelfAssessmentForm(
            b.criteria(),
            RatingLevel.HTTNV,
            null,
            null,
            null,
            "Nội dung khác",
            "Ưu",
            null,
            null,
            null);
    assertThat(ContentHash.of(profile, 1, c)).isNotEqualTo(ContentHash.of(profile, 1, b));
    assertThat(b.metCount()).isEqualTo(1);
  }

  private static List<SelfAssessmentForm.Criterion> criteria() {
    return List.of(new SelfAssessmentForm.Criterion("TCC-01", true));
  }

  private static SelfAssessmentForm form(
      List<SelfAssessmentForm.Criterion> criteria,
      RatingLevel rating,
      String breakthrough,
      String reason,
      String content) {
    return new SelfAssessmentForm(
        criteria, rating, breakthrough, null, reason, content, null, null, null, null);
  }
}
