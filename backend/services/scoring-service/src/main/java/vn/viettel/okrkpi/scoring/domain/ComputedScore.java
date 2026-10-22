package vn.viettel.okrkpi.scoring.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Kết quả tính của một hồ sơ trong một lô: tập đầu vào chuẩn hoá, kết quả, vết tính. Điểm tổng và
 * mức tự động nằm trong {@code result} và được tách ra để phát EVT-10.
 *
 * @param totalScore tổng điểm {@code numeric(6,3)} trong khoảng 0..100, {@code null} khi {@link
 *     ScoreStatus#LOI}
 */
public record ComputedScore(
    UUID resultId,
    UUID personId,
    String flow,
    UUID profileId,
    CanonicalDoc input,
    CanonicalDoc result,
    BigDecimal totalScore,
    String autoRatingCode,
    ScoreStatus status,
    List<TraceStep> trace) {

  private static final BigDecimal MAX_SCORE = BigDecimal.valueOf(100);

  public ComputedScore {
    Objects.requireNonNull(resultId, "resultId");
    Objects.requireNonNull(personId, "personId");
    Objects.requireNonNull(profileId, "profileId");
    if (!"DANG".equals(flow) && !"NHA_NUOC".equals(flow)) {
      throw new IllegalArgumentException("Luồng đánh giá không hợp lệ: " + flow);
    }
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(status, "status");
    trace = List.copyOf(trace);
    if (status == ScoreStatus.OK) {
      Objects.requireNonNull(totalScore, "totalScore");
      Objects.requireNonNull(autoRatingCode, "autoRatingCode");
      if (totalScore.signum() < 0
          || totalScore.compareTo(MAX_SCORE) > 0
          || totalScore.scale() > 3) {
        throw new IllegalArgumentException(
            "Tổng điểm phải trong 0..100, tối đa 3 chữ số thập phân");
      }
    }
    for (int i = 0; i < trace.size(); i++) {
      if (trace.get(i).stepNo() != i + 1) {
        throw new IllegalArgumentException("Vết tính phải đánh số bước liên tục từ 1");
      }
    }
  }
}
