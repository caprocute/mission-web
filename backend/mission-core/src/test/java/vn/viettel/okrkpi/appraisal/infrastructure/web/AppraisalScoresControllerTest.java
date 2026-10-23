package vn.viettel.okrkpi.appraisal.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import vn.viettel.okrkpi.appraisal.application.score.ScoreViewService;
import vn.viettel.okrkpi.appraisal.application.score.ScoreViewService.ScoreView;
import vn.viettel.okrkpi.appraisal.application.score.port.ScoreSnapshotReader.Snapshot;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.contract.appraisals.model.AppraisalScoreView;
import vn.viettel.okrkpi.contract.appraisals.model.Flow;
import vn.viettel.okrkpi.contract.appraisals.model.RatingLevel;
import vn.viettel.okrkpi.contract.appraisals.model.ScoreSnapshotKind;

class AppraisalScoresControllerTest {

  @SuppressWarnings("unchecked")
  private final AppraisalScoresController controller =
      new AppraisalScoresController(
          mock(ScoreViewService.class),
          mock(ObjectProvider.class),
          mock(ObjectProvider.class),
          mock(ObjectProvider.class));

  @Test
  void anhChupThanhPhanHoiHopDong() {
    UUID profileId = UUID.randomUUID();
    Snapshot s =
        new Snapshot(
            UUID.randomUUID(),
            profileId,
            "DANG",
            "CHOT_KY",
            UUID.randomUUID(),
            null,
            "1.2.0",
            new byte[] {(byte) 0xab},
            new byte[] {0x01, 0x0f},
            new byte[] {(byte) 0xff},
            new BigDecimal("72.5"),
            null,
            null,
            null,
            null,
            null,
            null,
            "DAM_BAO",
            "HTNV",
            "[{\"ratingCode\":\"HTTNV\",\"label\":\"Đủ điều kiện\",\"met\":false,\"x\":1}]",
            "[{\"fromRating\":\"HTTNV\",\"toRating\":\"HTNV\",\"text\":\"Hạ một mức\",\"legalRef\":\"Điều 5\"}]",
            "{}",
            "{\"giai_thich\":\"Điểm 72,5 thuộc mức Hoàn thành nhiệm vụ.\"}",
            Instant.parse("2027-04-05T00:00:00Z"),
            null);
    AppraisalScoreView out = controller.body(new ScoreView(profile(profileId), s));

    assertThat(out.getProfileId()).isEqualTo(profileId);
    assertThat(out.getFlow()).isEqualTo(Flow.DANG);
    assertThat(out.getSnapshot().getSnapshotKind()).isEqualTo(ScoreSnapshotKind.CHOT_KY);
    assertThat(out.getSnapshot().getInputHash()).isEqualTo("010f");
    assertThat(out.getSnapshot().getSnapshotHash()).isEqualTo("ff");
    assertThat(out.getSnapshot().getPolicyContentHash()).isEqualTo("ab");
    assertThat(out.getSnapshot().getAutoRatingCode()).isEqualTo(RatingLevel.HTNV);
    assertThat(out.getSnapshot().getRuleChecks()).hasSize(1);
    assertThat(out.getSnapshot().getDowngradeReasons()).hasSize(1);
    assertThat(out.getSnapshot().getGroups()).isEmpty();
    assertThat(out.getExplanation().getSystemLevel()).isEqualTo(RatingLevel.HTNV);
    assertThat(out.getExplanation().getText()).startsWith("Điểm 72,5");
    assertThat(out.getExplanation().getLegalRef()).isEqualTo("Điều 5");
  }

  private static ProfileSummary profile(UUID id) {
    return new ProfileSummary(
        id,
        UUID.randomUUID(),
        20271,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "DANG",
        AppraisalStatus.CHO_THAM_DINH,
        false,
        false,
        null,
        vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel.HTNV,
        null,
        null,
        null,
        0,
        false,
        null,
        null,
        null,
        1L);
  }
}
