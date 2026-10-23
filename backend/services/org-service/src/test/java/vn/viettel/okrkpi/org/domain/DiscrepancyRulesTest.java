package vn.viettel.okrkpi.org.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.org.domain.sync.DiscrepancyField;
import vn.viettel.okrkpi.org.domain.sync.DiscrepancyRules;
import vn.viettel.okrkpi.org.domain.sync.DiscrepancyRules.Finding;
import vn.viettel.okrkpi.org.domain.sync.DiscrepancyRules.PersonState;
import vn.viettel.okrkpi.org.domain.sync.DiscrepancyRules.Post;
import vn.viettel.okrkpi.org.domain.sync.Severity;
import vn.viettel.okrkpi.org.domain.sync.SuggestedAction;

/** DV-04-07 · QT-01: phân loại chênh lệch giữa dữ liệu nguồn và dữ liệu hiện hành. */
class DiscrepancyRulesTest {

  static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
  static final UUID UNIT_A = UUID.randomUUID();
  static final UUID UNIT_B = UUID.randomUUID();
  static final UUID TITLE = UUID.randomUUID();
  static final UUID HEAD_TITLE = UUID.randomUUID();

  private static Post post(UUID unit, UUID title, String kind, boolean head, LocalDate from) {
    return new Post(null, unit, title, kind, head, from);
  }

  private static PersonState state(String status, boolean party, Post... posts) {
    return new PersonState(status, party, List.of(posts));
  }

  @Test
  void identicalStateHasNoFinding() {
    PersonState s = state("DANG_CONG_TAC", true, post(UNIT_A, TITLE, "CHINH", false, TODAY.minusYears(1)));
    assertThat(DiscrepancyRules.compare(s, s, TODAY)).isEmpty();
  }

  @Test
  void primaryUnitChangeIsHigh_futureDateSuggestsFutureTransfer() {
    PersonState current = state("DANG_CONG_TAC", false, post(UNIT_A, TITLE, "CHINH", false, TODAY.minusYears(1)));
    List<Finding> past = DiscrepancyRules.compare(
        state("DANG_CONG_TAC", false, post(UNIT_B, TITLE, "CHINH", false, TODAY.minusDays(5))), current, TODAY);
    assertThat(past).singleElement().satisfies(f -> {
      assertThat(f.field()).isEqualTo(DiscrepancyField.ORG_UNIT);
      assertThat(f.severity()).isEqualTo(Severity.CAO);
      assertThat(f.action()).isEqualTo(SuggestedAction.LAY_THEO_NGUON);
    });
    List<Finding> future = DiscrepancyRules.compare(
        state("DANG_CONG_TAC", false, post(UNIT_B, TITLE, "CHINH", false, TODAY.plusDays(10))), current, TODAY);
    assertThat(future).singleElement().extracting(Finding::action)
        .isEqualTo(SuggestedAction.TAO_DIEU_DONG_TUONG_LAI);
  }

  @Test
  void statusAndPartyMembershipAreMedium_retirementEndsAssignments() {
    Post p = post(UNIT_A, TITLE, "CHINH", false, TODAY.minusYears(1));
    List<Finding> f = DiscrepancyRules.compare(state("NGHI_HUU", true, p), state("DANG_CONG_TAC", false, p), TODAY);
    assertThat(f).extracting(Finding::field)
        .containsExactlyInAnyOrder(DiscrepancyField.EMPLOYMENT_STATUS, DiscrepancyField.PARTY_MEMBERSHIP);
    assertThat(f).allSatisfy(x -> assertThat(x.severity()).isEqualTo(Severity.VUA));
    assertThat(f).filteredOn(x -> x.field() == DiscrepancyField.EMPLOYMENT_STATUS).singleElement()
        .extracting(Finding::action).isEqualTo(SuggestedAction.KET_THUC_PHAN_CONG);
  }

  @Test
  void headTitleChangeIsHigh_plainTitleChangeIsMedium() {
    Post plain = post(UNIT_A, TITLE, "CHINH", false, TODAY.minusYears(1));
    assertThat(DiscrepancyRules.compare(state("DANG_CONG_TAC", false,
        post(UNIT_A, HEAD_TITLE, "CHINH", true, TODAY.minusYears(1))), state("DANG_CONG_TAC", false, plain), TODAY))
        .singleElement().extracting(Finding::severity).isEqualTo(Severity.CAO);
    assertThat(DiscrepancyRules.compare(state("DANG_CONG_TAC", false,
        post(UNIT_A, HEAD_TITLE, "CHINH", false, TODAY.minusYears(1))), state("DANG_CONG_TAC", false, plain), TODAY))
        .singleElement().extracting(Finding::severity).isEqualTo(Severity.VUA);
  }

  @Test
  void concurrentPostOnOneSideIsLowUnlessHead() {
    Post primary = post(UNIT_A, TITLE, "CHINH", false, TODAY.minusYears(1));
    Post extra = post(UNIT_B, TITLE, "KIEM_NHIEM", false, TODAY.minusMonths(1));
    Post extraHead = post(UNIT_B, HEAD_TITLE, "KIEM_NHIEM", true, TODAY.minusMonths(1));
    assertThat(DiscrepancyRules.compare(state("DANG_CONG_TAC", false, primary, extra),
        state("DANG_CONG_TAC", false, primary), TODAY)).singleElement().satisfies(f -> {
          assertThat(f.severity()).isEqualTo(Severity.THAP);
          assertThat(f.action()).isEqualTo(SuggestedAction.LAY_THEO_NGUON);
        });
    assertThat(DiscrepancyRules.compare(state("DANG_CONG_TAC", false, primary),
        state("DANG_CONG_TAC", false, primary, extraHead), TODAY)).singleElement().satisfies(f -> {
          assertThat(f.severity()).isEqualTo(Severity.CAO);
          assertThat(f.action()).isEqualTo(SuggestedAction.KET_THUC_PHAN_CONG);
        });
  }

  @Test
  void personMismatchNeedsManualReview() {
    Finding f = DiscrepancyRules.personMismatch(TODAY);
    assertThat(f.field()).isEqualTo(DiscrepancyField.PERSON_MATCH);
    assertThat(f.severity()).isEqualTo(Severity.CAO);
    assertThat(f.action()).isEqualTo(SuggestedAction.DOI_SOAT_THU_CONG);
    assertThat(DiscrepancyField.ofCode("org_unit_id")).isEqualTo(DiscrepancyField.ORG_UNIT);
  }

  @Test
  void sourceWithoutEmploymentStatusIsNotCompared() {
    Post p = post(UNIT_A, TITLE, "CHINH", false, TODAY.minusYears(1));
    assertThat(DiscrepancyRules.compare(state(null, true, p), state("DANG_CONG_TAC", true, p), TODAY)).isEmpty();
  }

}
