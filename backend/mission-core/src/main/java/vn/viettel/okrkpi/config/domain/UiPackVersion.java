package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Aggregate phiên bản UI Pack ({@code config.ui_pack_version}, QT-18). Bất biến: chỉ sửa nội dung
 * khi {@code NHAP}; mọi chuyển theo {@link UiPackVersionStatus}; mỗi lần đổi tăng {@code
 * rowVersion}. Người soạn là {@code createdBy}; người gửi duyệt là {@code updatedBy} khi vào {@code
 * DANG_KIEM_TRA} và được giữ qua bước kiểm tra tự động để kiểm tách vai trò khi duyệt. {@code
 * bundleSha256} là băm gói dạng hex chữ thường, {@code bundleObjectKey} khoá đối tượng của gói,
 * {@code signature} chữ ký base64; ba trường có khi phát hành (CORE-08-11).
 */
public record UiPackVersion(
    UUID id,
    UUID uiPackId,
    String version,
    UiPackVersionStatus status,
    UiPackContent content,
    String bundleSha256,
    String bundleObjectKey,
    String signature,
    Instant publishedAt,
    UUID createdBy,
    Instant createdAt,
    UUID updatedBy,
    Instant updatedAt,
    int rowVersion) {

  /** Phiên bản ngữ nghĩa {@code chính.phụ.vá} (suy luận từ ví dụ của hợp đồng). */
  public static final Pattern VERSION =
      Pattern.compile("^(0|[1-9]\\d{0,5})(\\.(0|[1-9]\\d{0,5})){2}$");

  public UiPackVersion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(uiPackId, "uiPackId");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(createdBy, "createdBy");
    updatedBy = updatedBy == null ? createdBy : updatedBy;
    if (!VERSION.matcher(version).matches()) {
      throw new IllegalArgumentException("Phiên bản phải có dạng chính.phụ.vá, ví dụ 1.8.0");
    }
  }

  /** Bản nháp mới, phiên bản dòng 0. */
  public static UiPackVersion draft(
      UUID id, UUID uiPackId, String version, UiPackContent content, UUID author, Instant at) {
    return new UiPackVersion(
        id,
        uiPackId,
        version,
        UiPackVersionStatus.NHAP,
        content,
        null,
        null,
        null,
        null,
        author,
        at,
        author,
        at,
        0);
  }

  /** Lưu nháp: chỉ khi {@code NHAP}. */
  public UiPackVersion edit(UiPackContent newContent, UUID by, Instant at) {
    if (status != UiPackVersionStatus.NHAP) {
      throw new IllegalUiPackTransitionException(status, UiPackVersionStatus.NHAP);
    }
    return with(status, newContent, by, at);
  }

  /** Gửi duyệt: {@code NHAP → DANG_KIEM_TRA}, ghi nhận người gửi. */
  public UiPackVersion submit(UUID by, Instant at) {
    return move(UiPackVersionStatus.DANG_KIEM_TRA, by, at);
  }

  /** Kết quả kiểm tra tự động: đạt sang {@code CHO_DUYET}, không đạt về {@code NHAP}. */
  public UiPackVersion checked(boolean passed, Instant at) {
    if (status != UiPackVersionStatus.DANG_KIEM_TRA) {
      throw new IllegalUiPackTransitionException(
          status, passed ? UiPackVersionStatus.CHO_DUYET : UiPackVersionStatus.NHAP);
    }
    return move(passed ? UiPackVersionStatus.CHO_DUYET : UiPackVersionStatus.NHAP, updatedBy, at);
  }

  /** Người duyệt có phải người soạn hoặc người gửi duyệt không (tách vai trò, SCR-1801). */
  public boolean authoredBy(UUID reviewer) {
    return createdBy.equals(reviewer) || updatedBy.equals(reviewer);
  }

  /**
   * Từ chối: {@code CHO_DUYET → NHAP}.
   *
   * @throws SameReviewerException khi người duyệt là người soạn hoặc người gửi
   */
  public UiPackVersion reject(UUID reviewer, Instant at) {
    if (status != UiPackVersionStatus.CHO_DUYET) {
      throw new IllegalUiPackTransitionException(status, UiPackVersionStatus.NHAP);
    }
    if (authoredBy(reviewer)) {
      throw new SameReviewerException();
    }
    return move(UiPackVersionStatus.NHAP, reviewer, at);
  }

  /**
   * Phát hành: {@code CHO_DUYET → DA_PHAT_HANH}, ghi gói đã ký.
   *
   * @throws SameReviewerException khi người phát hành là người soạn hoặc người gửi
   */
  public UiPackVersion publish(
      UUID reviewer, Instant at, String sha256, String objectKey, String signatureBase64) {
    if (status != UiPackVersionStatus.CHO_DUYET) {
      throw new IllegalUiPackTransitionException(status, UiPackVersionStatus.DA_PHAT_HANH);
    }
    if (authoredBy(reviewer)) {
      throw new SameReviewerException();
    }
    return new UiPackVersion(
        id,
        uiPackId,
        version,
        UiPackVersionStatus.DA_PHAT_HANH,
        content,
        Objects.requireNonNull(sha256, "sha256"),
        Objects.requireNonNull(objectKey, "objectKey"),
        Objects.requireNonNull(signatureBase64, "signature"),
        at,
        createdBy,
        createdAt,
        reviewer,
        at,
        rowVersion + 1);
  }

  /** Bị phiên bản mới thay: {@code DA_PHAT_HANH → THAY_THE}. */
  public UiPackVersion supersede(UUID by, Instant at) {
    return move(UiPackVersionStatus.THAY_THE, by, at);
  }

  /**
   * Quay lui: {@code THAY_THE → DA_PHAT_HANH}, giữ nguyên gói đã ký và thời điểm phát hành (không
   * biên dịch, không ký lại; ADR-25).
   */
  public UiPackVersion reactivate(UUID by, Instant at) {
    if (bundleSha256 == null) {
      throw new IllegalUiPackTransitionException(status, UiPackVersionStatus.DA_PHAT_HANH);
    }
    return move(UiPackVersionStatus.DA_PHAT_HANH, by, at);
  }

  private UiPackVersion move(UiPackVersionStatus to, UUID by, Instant at) {
    if (!status.canMoveTo(to)) {
      throw new IllegalUiPackTransitionException(status, to);
    }
    return with(to, content, by, at);
  }

  private UiPackVersion with(UiPackVersionStatus s, UiPackContent c, UUID by, Instant at) {
    return new UiPackVersion(
        id,
        uiPackId,
        version,
        s,
        c,
        bundleSha256,
        bundleObjectKey,
        signature,
        publishedAt,
        createdBy,
        createdAt,
        by,
        at,
        rowVersion + 1);
  }

  /** Người duyệt trùng người soạn hoặc người gửi duyệt. */
  public static final class SameReviewerException extends RuntimeException {
    public SameReviewerException() {
      super("Người duyệt phải khác người soạn và người gửi duyệt");
    }
  }
}
