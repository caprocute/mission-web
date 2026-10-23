package vn.viettel.okrkpi.appraisal.application.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;

/** YC-PT-21: thao tác trên hồ sơ đánh giá theo trạng thái và tách vai BR-09-14. */
class AppraisalPermittedActionsTest {

  static final UUID PERSON = UUID.randomUUID();
  static final UUID APPRAISER = UUID.randomUUID();
  static final UUID DECIDER = UUID.randomUUID();
  static final UUID MANAGER = UUID.randomUUID();
  static final UUID ACCEPTOR = UUID.randomUUID();

  private static ProfileSummary profile(AppraisalStatus status, UUID appraiser) {
    return new ProfileSummary(
        UUID.randomUUID(),
        UUID.randomUUID(),
        20271,
        PERSON,
        UUID.randomUUID(),
        "DANG",
        status,
        false,
        false,
        null,
        null,
        null,
        appraiser,
        DECIDER,
        0,
        false,
        null,
        null,
        null,
        1);
  }

  @Test
  void selfAssessmentOnlyForAppraiseeDuringSelfAssessment() {
    ProfileSummary p = profile(AppraisalStatus.TU_DANH_GIA, null);
    assertThat(AppraisalPermittedActions.of(p, PERSON, Set::of))
        .containsExactly(AppraisalPermittedActions.LUU, AppraisalPermittedActions.KY_NOP);
    assertThat(AppraisalPermittedActions.of(p, MANAGER, Set::of)).isEmpty();
    assertThat(AppraisalPermittedActions.of(p, null, Set::of)).isEmpty();
  }

  @Test
  void appraisalExcludesAppraiseeAcceptorsAndDecider() {
    ProfileSummary p = profile(AppraisalStatus.CHO_THAM_DINH, null);
    AtomicInteger lookups = new AtomicInteger();
    assertThat(
            AppraisalPermittedActions.of(
                p,
                MANAGER,
                () -> {
                  lookups.incrementAndGet();
                  return Set.of(ACCEPTOR);
                }))
        .containsExactly(AppraisalPermittedActions.TRA_LAI, AppraisalPermittedActions.KY);
    assertThat(AppraisalPermittedActions.of(p, ACCEPTOR, () -> Set.of(ACCEPTOR))).isEmpty();
    assertThat(
            AppraisalPermittedActions.of(
                p,
                PERSON,
                () -> {
                  lookups.incrementAndGet();
                  return Set.of();
                }))
        .isEmpty();
    assertThat(AppraisalPermittedActions.of(p, DECIDER, Set::of)).isEmpty();
    assertThat(lookups).hasValue(1);
  }

  @Test
  void proposalByAnotherPersonAfterAppraisal() {
    ProfileSummary p = profile(AppraisalStatus.DA_THAM_DINH, APPRAISER);
    assertThat(AppraisalPermittedActions.of(p, MANAGER, Set::of))
        .containsExactly(AppraisalPermittedActions.DE_XUAT);
    assertThat(AppraisalPermittedActions.of(p, APPRAISER, Set::of)).isEmpty();
    assertThat(AppraisalPermittedActions.of(p, PERSON, Set::of)).isEmpty();
  }

  @Test
  void lockedProfileHasNoActions() {
    assertThat(
            AppraisalPermittedActions.of(profile(AppraisalStatus.DA_KHOA, null), PERSON, Set::of))
        .isEmpty();
  }
}
