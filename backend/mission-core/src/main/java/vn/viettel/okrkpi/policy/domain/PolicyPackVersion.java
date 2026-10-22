package vn.viettel.okrkpi.policy.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Phiên bản Policy Pack ({@code policy.policy_pack_version}) với máy trạng thái của baseline mục
 * 9.1. Bản ghi bất biến: mỗi chuyển trạng thái trả bản mới có {@code rowVersion} tăng một.
 *
 * @param manifestJson manifest dạng JSON (tham số, làm tròn, nhãn), đóng băng từ {@code
 *     DAT_KIEM_THU}
 * @param contentHash SHA-256 dạng hex của nội dung chuẩn hoá, có từ {@code DAT_KIEM_THU}
 * @param signed đã có chữ ký CAdES trên {@code content_hash}
 * @param sourceVersionId phiên bản của gói gốc mà phiên bản này kế thừa (BR-15-13)
 */
public record PolicyPackVersion(
    UUID id,
    UUID packId,
    String version,
    PolicyVersionStatus status,
    LocalDate effectiveFrom,
    String manifestJson,
    String contentHash,
    BigDecimal goldenPassRate,
    UUID lastTestRunId,
    boolean signed,
    UUID approvedBy,
    Instant approvedAt,
    Instant signedAt,
    Instant publishedAt,
    UUID sourceVersionId,
    String changeNote,
    Instant createdAt,
    UUID createdBy,
    Instant updatedAt,
    UUID updatedBy,
    long rowVersion) {

  public PolicyPackVersion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(packId, "packId");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(manifestJson, "manifestJson");
    Objects.requireNonNull(changeNote, "changeNote");
    if (SemVer.parse(version).isEmpty()) {
      throw new IllegalArgumentException("Phiên bản không đúng dạng ba phần: " + version);
    }
  }

  /** Phiên bản nháp mới (QT-15 bước 1). */
  public static PolicyPackVersion draft(
      UUID id,
      UUID packId,
      String version,
      String manifestJson,
      UUID sourceVersionId,
      String changeNote,
      UUID actor,
      Instant at) {
    return new PolicyPackVersion(
        id,
        packId,
        version,
        PolicyVersionStatus.NHAP,
        null,
        manifestJson,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        null,
        sourceVersionId,
        changeNote,
        at,
        actor,
        at,
        actor,
        0);
  }

  public SemVer semVer() {
    return SemVer.parse(version).orElseThrow();
  }

  /**
   * Phiên bản kế thừa gói gốc: ca vàng của gói cha phải chạy trên nó (TL-01 X-PP-11, CORE-05-07).
   */
  public boolean inheritsFromParent() {
    return sourceVersionId != null;
  }

  /**
   * Chuyển sang {@code target} nếu sơ đồ trạng thái và điều kiện kiểm soát cho phép (TL-02b QT-15):
   * đạt kiểm thử cần 100% ca vàng đạt, phát hành cần chữ ký và người duyệt khác người soạn.
   */
  public PolicyPackVersion moveTo(PolicyVersionStatus target, UUID actor, Instant at) {
    Objects.requireNonNull(target, "target");
    if (!status.canMoveTo(target)) {
      throw new PolicyTransitionException(
          "POLICY-409-VERSION-CONFLICT",
          "Phiên bản " + version + " không chuyển được từ " + status + " sang " + target);
    }
    if (target == PolicyVersionStatus.DAT_KIEM_THU
        && (goldenPassRate == null || goldenPassRate.compareTo(BigDecimal.ONE) != 0)) {
      throw new PolicyTransitionException(
          "POLICY-409-NOT-TESTED", "Phiên bản " + version + " chưa đạt toàn bộ ca kiểm thử vàng");
    }
    if (target == PolicyVersionStatus.DA_PHAT_HANH) {
      if (!signed || contentHash == null || approvedBy == null) {
        throw new PolicyTransitionException(
            "POLICY-409-NOT-SIGNED", "Phiên bản " + version + " chưa được ký số phê duyệt");
      }
      if (approvedBy.equals(createdBy)) {
        throw new PolicyTransitionException(
            "POLICY-403-SAME-AUTHOR", "Người phê duyệt phải khác người soạn phiên bản");
      }
    }
    return new PolicyPackVersion(
        id,
        packId,
        version,
        target,
        effectiveFrom,
        manifestJson,
        contentHash,
        goldenPassRate,
        lastTestRunId,
        signed,
        approvedBy,
        approvedAt,
        signedAt,
        target == PolicyVersionStatus.DA_PHAT_HANH && publishedAt == null ? at : publishedAt,
        sourceVersionId,
        changeNote,
        createdAt,
        createdBy,
        at,
        actor,
        rowVersion + 1);
  }
}
