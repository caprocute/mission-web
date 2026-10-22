package vn.viettel.okrkpi.product.domain.evidence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate phiên bản minh chứng ({@code product.evidence_version}) với máy trạng thái của QT-07
 * (TL-02a Hình 8.12): {@code NHAP → DA_NOP → DANG_NGHIEM_THU → DA_XAC_NHAN | YEU_CAU_BO_SUNG |
 * TU_CHOI}. Mỗi lệnh kiểm trạng thái nguồn và trả {@link EvidenceRuleViolation} khi sai; không lệnh
 * nào đưa phiên bản rời trạng thái cuối. Máy trạng thái này tách biệt với máy trạng thái nhiệm vụ
 * của MOD-02 (BR-07-10); liên động do {@link TaskInterlock} quyết định.
 *
 * <p>Bất biến: đối tượng không đổi tại chỗ, mỗi lệnh trả bản mới.
 */
public record EvidenceVersion(
    UUID id,
    UUID evidenceId,
    UUID deliverableId,
    UUID taskId,
    int versionNo,
    EvidenceStatus status,
    UUID objectId,
    byte[] contentSha256,
    String scanStatus,
    boolean aiPrefill,
    Instant userConfirmedAt,
    UUID submittedBy,
    Instant submittedAt,
    Instant acceptedAt,
    String note,
    UUID excludedPeriodId,
    long rowVersion) {

  /** Giá trị {@code scan_status} của tệp đã quét sạch. */
  public static final String SCAN_CLEAN = "SACH";

  public EvidenceVersion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
    contentSha256 = contentSha256 == null ? null : contentSha256.clone();
  }

  @Override
  public byte[] contentSha256() {
    return contentSha256 == null ? null : contentSha256.clone();
  }

  /** Thông tin tệp gắn vào bản nháp. */
  public record DraftContent(
      UUID objectId, byte[] contentSha256, String scanStatus, boolean aiPrefill, String note) {}

  /** Lưu nháp: chỉ ở {@code NHAP}; phiên bản đã nộp không sửa được (BR-07-01). */
  public EvidenceVersion saveDraft(DraftContent content) {
    Objects.requireNonNull(content, "content");
    if (status != EvidenceStatus.NHAP) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.VERSION_FROZEN,
          "Phiên bản minh chứng đã nộp không được sửa; hãy tạo phiên bản mới");
    }
    return with(
        EvidenceStatus.NHAP,
        content.objectId(),
        content.contentSha256(),
        content.scanStatus() == null ? scanStatus : content.scanStatus(),
        content.aiPrefill(),
        content.aiPrefill() ? userConfirmedAt : null,
        submittedBy,
        submittedAt,
        acceptedAt,
        content.note());
  }

  /** Người nộp xác nhận các ô do AI điền (BR-07-09). */
  public EvidenceVersion confirmAiFields(Instant at) {
    requireStatus(EvidenceStatus.NHAP);
    return with(
        status,
        objectId,
        contentSha256,
        scanStatus,
        aiPrefill,
        at,
        submittedBy,
        submittedAt,
        acceptedAt,
        note);
  }

  /**
   * Nộp: {@code NHAP → DA_NOP}; cần tệp có mã băm (BR-07-02), đã quét sạch (BR-07-04) và ô AI đã
   * xác nhận (BR-07-09).
   */
  public EvidenceVersion submit(UUID by, Instant at) {
    Objects.requireNonNull(by, "by");
    Objects.requireNonNull(at, "at");
    requireStatus(EvidenceStatus.NHAP);
    if (objectId == null || contentSha256 == null || contentSha256.length != 32) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.NO_OBJECT, "Phiên bản minh chứng chưa có tệp hợp lệ");
    }
    if (!SCAN_CLEAN.equals(scanStatus)) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.NOT_CLEAN, "Tệp minh chứng chưa được kiểm tra an toàn");
    }
    if (aiPrefill && userConfirmedAt == null) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.AI_NOT_CONFIRMED,
          "Vui lòng xác nhận các thông tin do hệ thống gợi ý trước khi nộp");
    }
    return with(
        EvidenceStatus.DA_NOP,
        objectId,
        contentSha256,
        scanStatus,
        aiPrefill,
        userConfirmedAt,
        by,
        at,
        acceptedAt,
        note);
  }

  /** Người nghiệm thu mở: {@code DA_NOP → DANG_NGHIEM_THU}. */
  public EvidenceVersion openReview() {
    requireStatus(EvidenceStatus.DA_NOP);
    return to(EvidenceStatus.DANG_NGHIEM_THU, acceptedAt, note);
  }

  /**
   * Xác nhận: {@code DANG_NGHIEM_THU → DA_XAC_NHAN}; chỉ phiên bản này được tính điểm (BR-07-05).
   */
  public EvidenceVersion accept(Instant at) {
    Objects.requireNonNull(at, "at");
    requireStatus(EvidenceStatus.DANG_NGHIEM_THU);
    return to(EvidenceStatus.DA_XAC_NHAN, at, note);
  }

  /** Yêu cầu bổ sung: {@code DANG_NGHIEM_THU → YEU_CAU_BO_SUNG}; nhận xét bắt buộc (BR-07-08). */
  public EvidenceVersion requestRevision(String comment) {
    requireStatus(EvidenceStatus.DANG_NGHIEM_THU);
    requireComment(comment);
    return to(EvidenceStatus.YEU_CAU_BO_SUNG, acceptedAt, note);
  }

  /** Từ chối: {@code DANG_NGHIEM_THU → TU_CHOI}; lý do bắt buộc (BR-07-08). */
  public EvidenceVersion reject(String reason) {
    requireStatus(EvidenceStatus.DANG_NGHIEM_THU);
    requireComment(reason);
    return to(EvidenceStatus.TU_CHOI, acceptedAt, note);
  }

  /**
   * Được tính điểm: {@code DA_XAC_NHAN} và không bị saga khoá kỳ gắn cờ "không tính trong kỳ"
   * ({@code excluded_period_id}, BR-08-05; domain chỉ đọc cờ này).
   */
  public boolean countsForScoring() {
    return status == EvidenceStatus.DA_XAC_NHAN && excludedPeriodId == null;
  }

  private void requireStatus(EvidenceStatus expected) {
    if (status != expected) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.INVALID_STATE,
          "Thao tác không phù hợp với trạng thái hiện tại của minh chứng");
    }
  }

  private static void requireComment(String comment) {
    if (comment == null || comment.isBlank()) {
      throw new EvidenceRuleViolation(
          EvidenceRuleViolation.COMMENT_REQUIRED, "Vui lòng nhập nhận xét");
    }
  }

  private EvidenceVersion to(EvidenceStatus next, Instant accepted, String newNote) {
    return with(
        next,
        objectId,
        contentSha256,
        scanStatus,
        aiPrefill,
        userConfirmedAt,
        submittedBy,
        submittedAt,
        accepted,
        newNote);
  }

  private EvidenceVersion with(
      EvidenceStatus next,
      UUID object,
      byte[] sha,
      String scan,
      boolean ai,
      Instant confirmed,
      UUID by,
      Instant submitted,
      Instant accepted,
      String newNote) {
    return new EvidenceVersion(
        id,
        evidenceId,
        deliverableId,
        taskId,
        versionNo,
        next,
        object,
        sha,
        scan,
        ai,
        confirmed,
        by,
        submitted,
        accepted,
        newNote,
        excludedPeriodId,
        rowVersion);
  }
}
