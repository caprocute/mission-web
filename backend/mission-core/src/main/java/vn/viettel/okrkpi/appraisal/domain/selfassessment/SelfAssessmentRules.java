package vn.viettel.okrkpi.appraisal.domain.selfassessment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentIssue.Kind;

/**
 * Quy tắc phiếu tự đánh giá ở máy chủ. Lưu nháp chỉ kiểm giới hạn độ dài và số lượng; nộp kiểm đủ
 * trường bắt buộc, giải trình khi khác mức tham khảo (BR-09-12, MSG-09-02), giải pháp đột phá kèm
 * minh chứng đã xác nhận khi tự đề xuất HTXSNV (MSG-09-03).
 */
public final class SelfAssessmentRules {

  /** Độ dài tối đa của mỗi trường văn bản (hợp đồng {@code AppraisalSelfAssessmentInput}). */
  public static final int MAX_TEXT = 2000;

  /** Độ dài tối thiểu của giải trình khi mức tự đề xuất khác mức tham khảo. */
  public static final int MIN_REASON = 20;

  /** Số tiêu chí chung tối đa trong một phiếu. */
  public static final int MAX_CRITERIA = 30;

  static final String MSG_REASON =
      "Mức tự đề xuất khác mức hệ thống tham khảo. Đồng chí cần ghi giải trình trước khi nộp"
          + " (QĐ Điều 16).";
  static final String MSG_BREAKTHROUGH =
      "Đề xuất mức Hoàn thành xuất sắc cần mô tả giải pháp đột phá và gắn ít nhất một minh chứng"
          + " đã được xác nhận.";

  private SelfAssessmentRules() {}

  /** Kiểm khi lưu nháp. */
  public static List<SelfAssessmentIssue> checkDraft(SelfAssessmentForm form) {
    List<SelfAssessmentIssue> issues = new ArrayList<>();
    if (form.criteria().size() > MAX_CRITERIA) {
      issues.add(
          new SelfAssessmentIssue(
              "criteria", Kind.VALIDATION, "Phiếu có quá nhiều tiêu chí chung.", null));
    }
    Set<String> codes = new HashSet<>();
    for (SelfAssessmentForm.Criterion c : form.criteria()) {
      if (c.code().isBlank() || c.code().length() > 16 || !codes.add(c.code())) {
        issues.add(
            new SelfAssessmentIssue(
                "criteria",
                Kind.VALIDATION,
                "Mã tiêu chí chung không hợp lệ hoặc bị trùng.",
                null));
        break;
      }
    }
    length(issues, "breakthrough", form.breakthrough());
    length(issues, "reason", form.reason());
    length(issues, "content", form.content());
    length(issues, "strengths", form.strengths());
    length(issues, "weaknesses", form.weaknesses());
    length(issues, "remedialPlan", form.remedialPlan());
    return issues;
  }

  /**
   * Kiểm khi nộp.
   *
   * @param autoRating mức hệ thống tham khảo của ảnh chụp điểm hiện hành
   * @param confirmedEvidence các minh chứng trong {@code breakthroughEvidenceIds} đang ở {@code
   *     DA_XAC_NHAN}
   */
  public static List<SelfAssessmentIssue> checkSubmit(
      SelfAssessmentForm form, RatingLevel autoRating, Set<UUID> confirmedEvidence) {
    List<SelfAssessmentIssue> issues = new ArrayList<>(checkDraft(form));
    if (form.criteria().isEmpty()) {
      issues.add(
          new SelfAssessmentIssue(
              "criteria",
              Kind.VALIDATION,
              "Đồng chí cần tự đánh giá các tiêu chí chung.",
              "BR-09-05"));
    }
    if (form.selfRating() == null) {
      issues.add(
          new SelfAssessmentIssue(
              "selfRatingCode", Kind.VALIDATION, "Đồng chí cần chọn mức tự đề xuất.", null));
    } else {
      if (autoRating != null && form.selfRating() != autoRating && !reasonOk(form.reason())) {
        issues.add(new SelfAssessmentIssue("reason", Kind.REASON, MSG_REASON, "BR-09-12"));
      }
      if (form.selfRating() == RatingLevel.HTXSNV
          && (blank(form.breakthrough())
              || form.breakthroughEvidenceIds().stream().noneMatch(confirmedEvidence::contains))) {
        issues.add(
            new SelfAssessmentIssue("breakthrough", Kind.BREAKTHROUGH, MSG_BREAKTHROUGH, null));
      }
    }
    if (blank(form.content())) {
      issues.add(
          new SelfAssessmentIssue(
              "content",
              Kind.VALIDATION,
              "Đồng chí cần ghi ưu điểm, hạn chế, hướng khắc phục.",
              null));
    }
    return issues;
  }

  /** Mức tự đề xuất khác mức tham khảo nên phải giải trình. */
  public static boolean differs(SelfAssessmentForm form, RatingLevel autoRating) {
    return form.selfRating() != null && autoRating != null && form.selfRating() != autoRating;
  }

  private static boolean reasonOk(String reason) {
    return reason != null && reason.strip().length() >= MIN_REASON && reason.length() <= MAX_TEXT;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static void length(List<SelfAssessmentIssue> issues, String field, String value) {
    if (value != null && value.length() > MAX_TEXT) {
      issues.add(
          new SelfAssessmentIssue(
              field, Kind.VALIDATION, "Nội dung dài quá " + MAX_TEXT + " ký tự.", null));
    }
  }
}
