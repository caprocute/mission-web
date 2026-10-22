package vn.viettel.okrkpi.product.domain.evidence;

import java.util.List;
import java.util.Objects;

/**
 * Điều kiện nộp phiên bản minh chứng nhiều tệp của QT-07 (SCR-0701, CORE-03-03), kiểm trước lệnh
 * {@link EvidenceVersion#submit}. Thứ tự kiểm cố định để người nộp nhận đúng một thông báo: cam
 * kết, có tệp, mã băm khớp (BR-07-02, MSG-07-03), tệp sạch (BR-07-04, MSG-07-02), giải trình tệp
 * trùng (BR-07-03, MSG-07-01). Ô do AI điền (BR-07-09) do {@link EvidenceVersion#submit} kiểm.
 */
public final class SubmissionRules {

  /** Số tệp tối đa của một phiên bản (hợp đồng {@code EvidenceVersionDraft.files}). */
  public static final int MAX_FILES = 20;

  private SubmissionRules() {}

  /** Ném {@link EvidenceRuleViolation} ở điều kiện đầu tiên chưa đạt. */
  public static void check(boolean attested, List<EvidenceFile> files) {
    Objects.requireNonNull(files, "files");
    if (!attested) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.ATTESTATION_REQUIRED,
          "Vui lòng xác nhận cam kết tính xác thực của minh chứng trước khi nộp");
    }
    if (files.isEmpty()) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.NO_OBJECT, "Phiên bản minh chứng chưa có tệp");
    }
    for (EvidenceFile f : files) {
      if (!f.hashMatches()) {
        throw new EvidenceRuleViolation(
            EvidenceRuleViolation.HASH_MISMATCH,
            "Tệp minh chứng đã thay đổi so với lúc đính kèm; vui lòng tải lại tệp");
      }
    }
    for (EvidenceFile f : files) {
      if (!EvidenceVersion.SCAN_CLEAN.equals(f.scanStatus())) {
        throw new EvidenceRuleViolation(
            EvidenceRuleViolation.NOT_CLEAN,
            "Tệp minh chứng chưa được kiểm tra an toàn hoặc không an toàn");
      }
    }
    for (EvidenceFile f : files) {
      if (f.duplicateSuspect() && !EvidenceFile.justificationValid(f.reuseJustification())) {
        throw new EvidenceRuleViolation(
            EvidenceRuleViolation.REUSE_JUSTIFICATION_REQUIRED,
            "Tệp này đã được dùng làm minh chứng cho nhiệm vụ khác; vui lòng nêu lý do dùng lại");
      }
    }
  }

  /**
   * Kết quả quét gộp của phiên bản: có tệp nhiễm thì {@code NHIEM}, có tệp lỗi thì {@code LOI}, còn
   * tệp chờ thì {@code CHO_QUET}, mọi tệp sạch thì {@code SACH}; không có tệp thì {@code CHO_QUET}.
   */
  public static String aggregateScan(List<String> scanStatuses) {
    if (scanStatuses.isEmpty()) {
      return "CHO_QUET";
    }
    if (scanStatuses.contains("NHIEM")) {
      return "NHIEM";
    }
    if (scanStatuses.contains("LOI")) {
      return "LOI";
    }
    if (scanStatuses.stream().allMatch(EvidenceVersion.SCAN_CLEAN::equals)) {
      return EvidenceVersion.SCAN_CLEAN;
    }
    return "CHO_QUET";
  }

  /**
   * Phiên bản kế tiếp chỉ được tạo khi chưa có phiên bản nào hoặc phiên bản mới nhất đã kết thúc ở
   * {@code DA_XAC_NHAN}, {@code YEU_CAU_BO_SUNG} hoặc {@code TU_CHOI}.
   */
  public static void requireCanOpenNext(EvidenceStatus latest) {
    if (latest != null
        && (latest == EvidenceStatus.NHAP
            || latest == EvidenceStatus.DA_NOP
            || latest == EvidenceStatus.DANG_NGHIEM_THU)) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.OPEN_VERSION_EXISTS,
          "Minh chứng đang có phiên bản chưa hoàn tất; vui lòng hoàn tất phiên bản đó trước");
    }
  }
}
