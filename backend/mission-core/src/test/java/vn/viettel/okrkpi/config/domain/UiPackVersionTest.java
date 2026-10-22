package vn.viettel.okrkpi.config.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Máy trạng thái phiên bản UI Pack theo bảng trạng thái QT-18 (CORE-08-08). */
class UiPackVersionTest {

  private static final Instant AT = Instant.parse("2027-01-04T01:00:00Z");
  private static final UUID AUTHOR = UUID.randomUUID();
  private static final UiPackContent CONTENT =
      new UiPackContent("{}", "{}", "[]", "1.0.0", null, true, false);

  static UiPackVersion draft() {
    return UiPackVersion.draft(UUID.randomUUID(), UUID.randomUUID(), "1.8.0", CONTENT, AUTHOR, AT);
  }

  @ParameterizedTest
  @CsvSource({
    "NHAP, DANG_KIEM_TRA",
    "DANG_KIEM_TRA, NHAP",
    "DANG_KIEM_TRA, CHO_DUYET",
    "CHO_DUYET, NHAP",
    "CHO_DUYET, DA_PHAT_HANH",
    "DA_PHAT_HANH, THAY_THE",
    "THAY_THE, DA_PHAT_HANH"
  })
  void sevenValidTransitions(UiPackVersionStatus from, UiPackVersionStatus to) {
    assertThat(from.canMoveTo(to)).isTrue();
  }

  @Test
  void everyOtherTransitionIsRejected() {
    int valid = 0;
    for (UiPackVersionStatus from : UiPackVersionStatus.values()) {
      for (UiPackVersionStatus to : UiPackVersionStatus.values()) {
        if (from.canMoveTo(to)) {
          valid++;
        }
      }
    }
    assertThat(valid).isEqualTo(7);
    assertThat(UiPackVersionStatus.CHO_DUYET.inProgress()).isTrue();
    assertThat(UiPackVersionStatus.THAY_THE.inProgress()).isFalse();
  }

  @Test
  void draftEditSubmitCheckAndRejectCycle() {
    UiPackVersion v = draft();
    assertThat(v.status()).isEqualTo(UiPackVersionStatus.NHAP);
    assertThat(v.rowVersion()).isZero();
    UiPackContent edited = new UiPackContent("{\"a\":1}", "{}", null, "1.0.1", null, false, true);
    UiPackVersion e = v.edit(edited, AUTHOR, AT);
    assertThat(e.content()).isEqualTo(edited);
    assertThat(e.content().assetsJson()).isEqualTo("[]");
    assertThat(e.rowVersion()).isEqualTo(1);

    UUID submitter = UUID.randomUUID();
    UiPackVersion s = e.submit(submitter, AT);
    assertThat(s.status()).isEqualTo(UiPackVersionStatus.DANG_KIEM_TRA);
    assertThat(s.updatedBy()).isEqualTo(submitter);
    assertThatThrownBy(() -> s.edit(edited, AUTHOR, AT))
        .isInstanceOf(IllegalUiPackTransitionException.class)
        .satisfies(
            ex -> {
              IllegalUiPackTransitionException t = (IllegalUiPackTransitionException) ex;
              assertThat(t.from()).isEqualTo(UiPackVersionStatus.DANG_KIEM_TRA);
              assertThat(t.to()).isEqualTo(UiPackVersionStatus.NHAP);
            });
    assertThatThrownBy(() -> s.submit(AUTHOR, AT))
        .isInstanceOf(IllegalUiPackTransitionException.class);
    assertThatThrownBy(() -> s.reject(UUID.randomUUID(), AT))
        .isInstanceOf(IllegalUiPackTransitionException.class);

    assertThat(s.checked(false, AT).status()).isEqualTo(UiPackVersionStatus.NHAP);
    UiPackVersion waiting = s.checked(true, AT);
    assertThat(waiting.status()).isEqualTo(UiPackVersionStatus.CHO_DUYET);
    assertThat(waiting.updatedBy()).as("người gửi được giữ").isEqualTo(submitter);
    assertThatThrownBy(() -> waiting.checked(true, AT))
        .isInstanceOf(IllegalUiPackTransitionException.class);

    assertThatThrownBy(() -> waiting.reject(AUTHOR, AT))
        .isInstanceOf(UiPackVersion.SameReviewerException.class);
    assertThatThrownBy(() -> waiting.reject(submitter, AT))
        .isInstanceOf(UiPackVersion.SameReviewerException.class);
    UUID reviewer = UUID.randomUUID();
    UiPackVersion back = waiting.reject(reviewer, AT);
    assertThat(back.status()).isEqualTo(UiPackVersionStatus.NHAP);
    assertThat(back.rowVersion()).isEqualTo(4);
    assertThat(back.createdBy()).isEqualTo(AUTHOR);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.8", "v1.8.0", "01.2.3", "1.2.3-beta", ""})
  void versionMustBeSemantic(String version) {
    assertThatThrownBy(
            () ->
                UiPackVersion.draft(
                    UUID.randomUUID(), UUID.randomUUID(), version, CONTENT, AUTHOR, AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void contentRequiresRegistryVersion() {
    assertThatThrownBy(() -> new UiPackContent("{}", "{}", "[]", " ", null, false, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new UiPackContent("{}", "{}", "[]", "x".repeat(33), null, false, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new UiPack(UUID.randomUUID(), "mac-dinh", "Giao diện", null, 0).code())
        .isEqualTo("mac-dinh");
  }
}
