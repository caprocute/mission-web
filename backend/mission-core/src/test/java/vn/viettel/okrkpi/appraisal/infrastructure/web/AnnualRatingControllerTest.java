package vn.viettel.okrkpi.appraisal.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.annual.AnnualRatingService.Explanation;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.AnnualRow;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.QuarterRef;
import vn.viettel.okrkpi.appraisal.application.score.port.ScoreSnapshotReader.Snapshot;
import vn.viettel.okrkpi.contract.appraisals.model.AnnualRatingExplanation;
import vn.viettel.okrkpi.contract.appraisals.model.AnnualRatingRow;
import vn.viettel.okrkpi.contract.appraisals.model.RatingLevel;

class AnnualRatingControllerTest {

  @Test
  void dongNamVaGiaiThichTheoHopDong() {
    AnnualRow r =
        new AnnualRow(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            "Phòng Tổng hợp",
            "DANG",
            "CHO_QUYET_DINH",
            Arrays.asList("HTTNV", "KHTNV", "HTNV", null),
            new BigDecimal("80.5"),
            "HTTNV",
            "HTNV",
            null,
            true,
            false,
            "CO_QUY_KHTNV",
            false,
            false,
            4L,
            UUID.randomUUID(),
            2029,
            null,
            null);
    AnnualRatingRow row = AnnualRatingController.row(r);
    assertThat(row.getQuarterRatings())
        .containsExactly(RatingLevel.HTTNV, RatingLevel.KHTNV, RatingLevel.HTNV, null);
    assertThat(row.getPerson().getName()).isEmpty();
    assertThat(row.getSystemLevel()).isEqualTo(RatingLevel.HTNV);
    assertThat(row.getRatingCode()).isNull();
    assertThat(row.getLinkageNote()).contains("15.5");
    assertThat(row.getRowVersion()).isEqualTo(4);

    QuarterRef q2 =
        new QuarterRef(
            2,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "DA_KY_SO",
            "KHTNV",
            UUID.randomUUID(),
            new byte[] {1},
            new BigDecimal("55"),
            "1.4",
            UUID.randomUUID());
    Snapshot s =
        new Snapshot(
            UUID.randomUUID(),
            r.profileId(),
            "DANG",
            "CHOT_KY",
            UUID.randomUUID(),
            null,
            "2.0",
            null,
            new byte[] {0x0a},
            new byte[] {0x0b},
            new BigDecimal("80.5"),
            null,
            null,
            null,
            null,
            new BigDecimal("0.4"),
            null,
            null,
            "HTNV",
            "[{\"ratingCode\":\"HTXSNV\",\"label\":\"Không có quý không hoàn thành\",\"met\":false}]",
            "[]",
            "{}",
            null,
            Instant.parse("2030-01-11T00:00:00Z"),
            null);
    AnnualRatingExplanation e =
        AnnualRatingController.explanation(
            new Explanation(r, List.of(q2), s, "HTNV", "(1 + 2) / 2 = 1,5", "Câu", "QĐ"));
    assertThat(e.getQuarters()).hasSize(1);
    assertThat(e.getQuarters().get(0).getPeriod().getLabel()).isEqualTo("Quý II/2029");
    assertThat(e.getQuarters().get(0).getPeriod().getEndDate()).hasToString("2029-06-30");
    assertThat(e.getConditions()).hasSize(1);
    assertThat(e.getInputHash()).isEqualTo("0a");
    assertThat(e.getPolicyVersion()).isEqualTo("2.0");
    assertThat(e.getFormula()).isEqualTo("(1 + 2) / 2 = 1,5");
    AnnualRatingExplanation bare =
        AnnualRatingController.explanation(
            new Explanation(r, List.of(), null, "HTNV", null, null, null));
    assertThat(bare.getConditions()).isEmpty();
    assertThat(bare.getText()).isNull();
  }
}
