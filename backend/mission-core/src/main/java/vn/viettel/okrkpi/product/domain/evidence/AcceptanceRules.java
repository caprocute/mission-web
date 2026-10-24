package vn.viettel.okrkpi.product.domain.evidence;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Điều kiện quyết định nghiệm thu của QT-07 (SCR-0703, CORE-03-07). Xác nhận: mức chất lượng thuộc
 * năm mức (BR-07-07), mọi tiêu chuẩn đã được đánh giá (MSG-07-09), mức dưới 1 phải có nhận xét
 * (BR-07-08, MSG-07-10), đã có phiên bản bị yêu cầu bổ sung thì phải nêu nguyên nhân chỉnh sửa. Yêu
 * cầu bổ sung và từ chối: nhận xét hoặc lý do bắt buộc (BR-07-08).
 */
public final class AcceptanceRules {

  /** Năm mức chất lượng của Phụ lục 3 mục 2. */
  public static final List<BigDecimal> QUALITY_LEVELS =
      List.of(
          BigDecimal.ONE,
          new BigDecimal("0.75"),
          new BigDecimal("0.5"),
          new BigDecimal("0.25"),
          BigDecimal.ZERO);

  /** Độ dài tối đa của nhận xét (hợp đồng {@code comment}). */
  public static final int MAX_COMMENT = 2000;

  /** Độ dài tối đa của ghi chú một tiêu chuẩn. */
  public static final int MAX_NOTE = 500;

  private AcceptanceRules() {}

  /**
   * Kiểm xác nhận nghiệm thu.
   *
   * @param required mã tiêu chuẩn phải có kết quả (đã đánh giá ở các phiên bản trước)
   * @param revised đã có phiên bản trước của minh chứng bị yêu cầu bổ sung
   */
  public static void checkAccept(
      BigDecimal quality,
      List<CriterionResult> criteria,
      Set<String> required,
      boolean revised,
      String revisionCause,
      Boolean objectiveRevision,
      String comment) {
    if (quality == null || QUALITY_LEVELS.stream().noneMatch(q -> q.compareTo(quality) == 0)) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.VALIDATION, "Mức chất lượng chỉ nhận 1; 0,75; 0,5; 0,25 hoặc 0");
    }
    // Phải đánh giá đủ các tiêu chuẩn bắt buộc; nhiệm vụ không có tiêu chuẩn có cấu trúc nào (danh
    // sách
    // tiêu chuẩn của phiên bản rỗng) thì danh sách kết quả rỗng là đủ (QA-05 HT-21).
    checkCriteria(criteria, false);
    Set<String> given = new HashSet<>();
    criteria.forEach(c -> given.add(c.code().strip()));
    if (!given.containsAll(required)) {
      throw incomplete();
    }
    checkLength(comment);
    if (quality.compareTo(BigDecimal.ONE) < 0 && blank(comment)) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.COMMENT_REQUIRED, "Mức chất lượng dưới 1 cần có nhận xét.");
    }
    if (revised && (blank(revisionCause) || objectiveRevision == null)) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.REVISION_CAUSE_REQUIRED,
          "Vui lòng chọn nguyên nhân chỉnh sửa vì minh chứng đã từng được yêu cầu bổ sung");
    }
  }

  /** Kiểm yêu cầu bổ sung hoặc từ chối: nhận xét bắt buộc, tiêu chuẩn (nếu có) hợp lệ. */
  public static void checkReturn(List<CriterionResult> criteria, String comment) {
    checkCriteria(criteria, false);
    checkLength(comment);
    if (blank(comment)) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.COMMENT_REQUIRED, "Vui lòng nhập nhận xét hoặc lý do.");
    }
  }

  private static void checkCriteria(List<CriterionResult> criteria, boolean requireAny) {
    if (criteria == null || (requireAny && criteria.isEmpty())) {
      throw incomplete();
    }
    Set<String> seen = new HashSet<>();
    for (CriterionResult c : criteria) {
      if (c == null || blank(c.code()) || !seen.add(c.code().strip())) {
        throw incomplete();
      }
      if (c.note() != null && c.note().length() > MAX_NOTE) {
        throw new EvidenceRuleViolation(
            EvidenceRuleViolation.VALIDATION, "Ghi chú tiêu chuẩn tối đa 500 ký tự");
      }
    }
  }

  private static void checkLength(String comment) {
    if (comment != null && comment.length() > MAX_COMMENT) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.VALIDATION, "Nhận xét tối đa 2.000 ký tự");
    }
  }

  private static EvidenceRuleViolation incomplete() {
    return new EvidenceRuleViolation(
        EvidenceRuleViolation.CRITERIA_INCOMPLETE,
        "Vui lòng đánh giá tất cả tiêu chuẩn chất lượng trước khi chọn mức.");
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
