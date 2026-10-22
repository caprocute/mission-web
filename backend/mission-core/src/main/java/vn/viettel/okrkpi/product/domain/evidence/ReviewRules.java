package vn.viettel.okrkpi.product.domain.evidence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Điều kiện mở nghiệm thu một phiên bản minh chứng của QT-07 (SCR-0703, CORE-03-06). Thứ tự kiểm cố
 * định: người mở, trạng thái, toàn vẹn tệp (MSG-07-13), tệp sạch (BR-07-04).
 */
public final class ReviewRules {

  private ReviewRules() {}

  /**
   * Người mở phải là người nghiệm thu được chỉ định và không là người nộp, người chủ trì hay người
   * phối hợp của nhiệm vụ (BR-07-06).
   */
  public static void requireIndependentReviewer(
      UUID actor, UUID designated, UUID submitter, UUID lead, boolean coordinator) {
    Objects.requireNonNull(actor, "actor");
    if (!actor.equals(designated)) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.SELF_REVIEW,
          "Bạn không phải người nghiệm thu được chỉ định của minh chứng này");
    }
    if (actor.equals(submitter) || actor.equals(lead) || coordinator) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.SELF_REVIEW,
          "Người nộp hoặc người thực hiện nhiệm vụ không được tự nghiệm thu minh chứng");
    }
  }

  /** Chỉ mở được phiên bản {@code DA_NOP} hoặc đang {@code DANG_NGHIEM_THU}. */
  public static void requireReviewable(EvidenceStatus status) {
    if (status != EvidenceStatus.DA_NOP && status != EvidenceStatus.DANG_NGHIEM_THU) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.INVALID_STATE,
          "Phiên bản minh chứng này không ở trạng thái chờ nghiệm thu");
    }
  }

  /**
   * Tệp có mã băm hoặc phiên bản đối tượng không còn khớp bản ghi của SVC-08; rỗng khi toàn vẹn.
   */
  public static List<EvidenceFile> tampered(List<EvidenceFile> files) {
    return files.stream().filter(f -> !f.hashMatches()).toList();
  }

  /** Mọi tệp phải đang {@code SACH}; kết quả quét lại sau khi nộp có thể đổi trạng thái tệp. */
  public static void requireClean(List<EvidenceFile> files) {
    for (EvidenceFile f : files) {
      if (!EvidenceVersion.SCAN_CLEAN.equals(f.scanStatus())) {
        throw new EvidenceRuleViolation(
            EvidenceRuleViolation.NOT_CLEAN,
            "Tệp minh chứng chưa được kiểm tra an toàn hoặc không an toàn");
      }
    }
  }
}
