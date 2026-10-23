package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** YC-PT-21: thao tác được làm trên mục tiêu và KR theo trạng thái và người gọi. */
class OkrPermittedActionsTest {

  static final UUID ME = UUID.randomUUID();
  static final UUID OTHER = UUID.randomUUID();

  @Test
  void draftUnitObjectiveCanBeComposedAndSubmitted() {
    assertThat(OkrPermittedActions.objective("NHAP", "DON_VI", null, OTHER, ME))
        .containsExactly(OkrPermittedActions.SOAN_DON_VI, OkrPermittedActions.TRINH);
  }

  @Test
  void personalDraftOnlyForItsOwner() {
    assertThat(OkrPermittedActions.objective("NHAP", "CA_NHAN", ME, ME, ME))
        .containsExactly(OkrPermittedActions.SOAN_CA_NHAN, OkrPermittedActions.TRINH);
    assertThat(OkrPermittedActions.objective("NHAP", "CA_NHAN", OTHER, OTHER, ME)).isEmpty();
  }

  @Test
  void pendingObjectiveReviewedByAnotherPerson() {
    assertThat(OkrPermittedActions.objective("CHO_DUYET", "DON_VI", null, OTHER, ME))
        .containsExactly(OkrPermittedActions.DUYET, OkrPermittedActions.TRA_LAI);
    assertThat(OkrPermittedActions.objective("CHO_DUYET", "DON_VI", null, ME, ME)).isEmpty();
    assertThat(OkrPermittedActions.objective("DA_DUYET", "DON_VI", null, OTHER, ME)).isEmpty();
    assertThat(OkrPermittedActions.objective("NHAP", "DON_VI", null, OTHER, null)).isEmpty();
  }

  @Test
  void checkInForOwnerOrDelegateOfApprovedObjective() {
    assertThat(OkrPermittedActions.keyResult("DA_DUYET", ME, OTHER, ME, false))
        .containsExactly(OkrPermittedActions.CHECK_IN);
    assertThat(OkrPermittedActions.keyResult("DA_DUYET", null, ME, ME, false))
        .containsExactly(OkrPermittedActions.CHECK_IN);
    assertThat(OkrPermittedActions.keyResult("DA_DUYET", OTHER, OTHER, ME, true))
        .containsExactly(OkrPermittedActions.CHECK_IN);
    assertThat(OkrPermittedActions.keyResult("DA_DUYET", OTHER, OTHER, ME, false)).isEmpty();
    assertThat(OkrPermittedActions.keyResult("NHAP", ME, ME, ME, false)).isEmpty();
    assertThat(OkrPermittedActions.keyResult("DA_DUYET", ME, ME, null, false)).isEmpty();
  }

  @Test
  void ownsKeyResultFallsBackToCreator() {
    assertThat(OkrPermittedActions.ownsKeyResult(null, ME, ME)).isTrue();
    assertThat(OkrPermittedActions.ownsKeyResult(OTHER, ME, ME)).isFalse();
    assertThat(OkrPermittedActions.ownsKeyResult(ME, ME, null)).isFalse();
  }
}
