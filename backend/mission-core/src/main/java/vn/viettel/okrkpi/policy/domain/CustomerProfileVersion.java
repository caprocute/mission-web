package vn.viettel.okrkpi.policy.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Phiên bản hồ sơ tập khách hàng ({@code policy.customer_profile_version}, BS-03 ADR-33) với cùng
 * máy trạng thái của phiên bản Policy Pack (baseline mục 9.1). Bản ghi bất biến: mỗi lệnh trả bản
 * mới có {@code rowVersion} tăng một.
 *
 * @param manifestJson bản kê dạng JSON ({@code CustomerProfileManifest}), đóng băng từ {@code
 *     CHO_PHE_DUYET}
 * @param contentHash SHA-256 dạng hex của bản kê và khung tham số, có từ {@code DAT_KIEM_THU}
 */
public record CustomerProfileVersion(
    UUID id,
    UUID profileId,
    String version,
    PolicyVersionStatus status,
    String manifestJson,
    String contentHash,
    boolean signed,
    UUID approvedBy,
    Instant approvedAt,
    Instant signedAt,
    Instant publishedAt,
    String changeNote,
    Instant createdAt,
    UUID createdBy,
    Instant updatedAt,
    UUID updatedBy,
    long rowVersion) {

  public CustomerProfileVersion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(manifestJson, "manifestJson");
    Objects.requireNonNull(changeNote, "changeNote");
    if (SemVer.parse(version).isEmpty()) {
      throw new IllegalArgumentException("Phiên bản không đúng dạng ba phần: " + version);
    }
  }

  /** Phiên bản nháp mới. */
  public static CustomerProfileVersion draft(
      UUID id,
      UUID profileId,
      String version,
      String manifestJson,
      String changeNote,
      UUID actor,
      Instant at) {
    return new CustomerProfileVersion(
        id,
        profileId,
        version,
        PolicyVersionStatus.NHAP,
        manifestJson,
        null,
        false,
        null,
        null,
        null,
        null,
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
   * Nội dung còn sửa được: {@code NHAP} hoặc {@code DAT_KIEM_THU} (sửa thì về {@code NHAP}); từ
   * {@code CHO_PHE_DUYET} trở đi là {@code POLICY-409-VERSION-FROZEN}.
   */
  public void requireEditable() {
    if (status != PolicyVersionStatus.NHAP && status != PolicyVersionStatus.DAT_KIEM_THU) {
      throw new PolicyTransitionException(
          "POLICY-409-VERSION-FROZEN",
          "Phiên bản " + version + " đang chờ phê duyệt hoặc đã phát hành nên không sửa được");
    }
  }

  /** Sửa bản kê; phiên bản {@code DAT_KIEM_THU} về {@code NHAP} và bỏ băm nội dung. */
  public CustomerProfileVersion edit(String newManifest, String newNote, UUID actor, Instant at) {
    requireEditable();
    return new CustomerProfileVersion(
        id,
        profileId,
        version,
        PolicyVersionStatus.NHAP,
        Objects.requireNonNull(newManifest, "newManifest"),
        null,
        false,
        null,
        null,
        null,
        null,
        newNote == null ? changeNote : newNote,
        createdAt,
        createdBy,
        at,
        actor,
        rowVersion + 1);
  }

  /** Kiểm tra đạt: {@code NHAP} → {@code DAT_KIEM_THU} kèm băm nội dung. */
  public CustomerProfileVersion validated(String hash, UUID actor, Instant at) {
    Objects.requireNonNull(hash, "hash");
    if (status != PolicyVersionStatus.NHAP) {
      throw new PolicyTransitionException(
          "POLICY-409-VERSION-CONFLICT", "Phiên bản " + version + " không ở trạng thái nháp");
    }
    return copy(PolicyVersionStatus.DAT_KIEM_THU, hash, false, null, null, null, actor, at);
  }

  /** Gửi phê duyệt: {@code DAT_KIEM_THU} → {@code CHO_PHE_DUYET}. */
  public CustomerProfileVersion submit(UUID actor, Instant at) {
    if (status != PolicyVersionStatus.DAT_KIEM_THU || contentHash == null) {
      throw new PolicyTransitionException(
          "POLICY-409-NOT-TESTED", "Phiên bản " + version + " chưa kiểm tra đạt nên chưa gửi được");
    }
    return copy(PolicyVersionStatus.CHO_PHE_DUYET, contentHash, false, null, null, null, actor, at);
  }

  /** Kiểm trước khi ký: đang chờ phê duyệt, người duyệt khác người soạn (BR-15-09). */
  public void requireApprovableBy(UUID approver) {
    requirePending();
    if (approver.equals(createdBy)) {
      throw new PolicyTransitionException(
          "POLICY-403-SAME-AUTHOR", "Người phê duyệt phải khác người soạn phiên bản");
    }
  }

  /**
   * Ký phê duyệt và phát hành: {@code CHO_PHE_DUYET} → {@code DA_PHAT_HANH} với chữ ký, người duyệt
   * khác người soạn.
   */
  public CustomerProfileVersion approve(UUID approver, Instant signedAt, Instant at) {
    Objects.requireNonNull(signedAt, "signedAt");
    requireApprovableBy(approver);
    return new CustomerProfileVersion(
        id,
        profileId,
        version,
        PolicyVersionStatus.DA_PHAT_HANH,
        manifestJson,
        contentHash,
        true,
        approver,
        at,
        signedAt,
        at,
        changeNote,
        createdAt,
        createdBy,
        at,
        approver,
        rowVersion + 1);
  }

  /**
   * Từ chối có ý kiến: {@code CHO_PHE_DUYET} → {@code NHAP}; băm cũ giữ nguyên vì trigger đóng băng
   * nội dung từ {@code CHO_PHE_DUYET}, lần sửa hoặc kiểm tra sau sẽ ghi lại.
   */
  public CustomerProfileVersion reject(UUID actor, Instant at) {
    requirePending();
    return copy(PolicyVersionStatus.NHAP, contentHash, false, null, null, null, actor, at);
  }

  private void requirePending() {
    if (status != PolicyVersionStatus.CHO_PHE_DUYET) {
      throw new PolicyTransitionException(
          "POLICY-409-NOT-PENDING", "Phiên bản " + version + " không ở trạng thái chờ phê duyệt");
    }
  }

  private CustomerProfileVersion copy(
      PolicyVersionStatus newStatus,
      String newHash,
      boolean newSigned,
      UUID newApprovedBy,
      Instant newApprovedAt,
      Instant newSignedAt,
      UUID actor,
      Instant at) {
    return new CustomerProfileVersion(
        id,
        profileId,
        version,
        newStatus,
        manifestJson,
        newHash,
        newSigned,
        newApprovedBy,
        newApprovedAt,
        newSignedAt,
        publishedAt,
        changeNote,
        createdAt,
        createdBy,
        at,
        actor,
        rowVersion + 1);
  }
}
