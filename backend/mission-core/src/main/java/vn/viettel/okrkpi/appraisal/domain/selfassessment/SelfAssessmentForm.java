package vn.viettel.okrkpi.appraisal.domain.selfassessment;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;

/**
 * Nội dung phiếu tự đánh giá dạng rõ, chỉ tồn tại trong bộ nhớ; khi lưu, các trường văn bản (C2)
 * được mã hoá trường, phần còn lại vào {@code structured_data}. Mọi trường có thể rỗng khi lưu
 * nháp.
 */
public record SelfAssessmentForm(
    List<Criterion> criteria,
    RatingLevel selfRating,
    String breakthrough,
    List<UUID> breakthroughEvidenceIds,
    String reason,
    String content,
    String strengths,
    String weaknesses,
    String remedialPlan,
    List<Map<String, Object>> claims) {

  public SelfAssessmentForm {
    criteria = criteria == null ? List.of() : List.copyOf(criteria);
    breakthroughEvidenceIds =
        breakthroughEvidenceIds == null ? List.of() : List.copyOf(breakthroughEvidenceIds);
    claims = claims == null ? List.of() : List.copyOf(claims);
  }

  /** Phiếu trống của hồ sơ chưa lưu nháp lần nào. */
  public static SelfAssessmentForm empty() {
    return new SelfAssessmentForm(null, null, null, null, null, null, null, null, null, null);
  }

  /** Một nhóm tiêu chí chung tự nhận đạt hay không đạt. */
  public record Criterion(String code, boolean met) {
    public Criterion {
      Objects.requireNonNull(code, "code");
    }
  }

  /** Số tiêu chí tự nhận đạt. */
  public long metCount() {
    return criteria.stream().filter(Criterion::met).count();
  }
}
