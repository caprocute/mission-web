package vn.viettel.okrkpi.oversight.domain.postaudit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Một đợt hậu kiểm ({@code oversight.post_audit}): mở khi khởi tạo, đóng khi người kiểm tra ghi kết
 * luận, phát hiện (C2, chỉ giữ bản mã), yêu cầu mở lại liên quan và tệp bằng chứng xuất từ sổ cái.
 */
public record PostAudit(
    UUID id,
    UUID periodId,
    int periodBucket,
    String auditKind,
    String targetType,
    UUID targetId,
    String basis,
    UUID inspectorId,
    byte[] findingsCipher,
    UUID dekId,
    String conclusion,
    UUID reopenRequestId,
    List<UUID> evidenceObjectIds,
    Instant openedAt,
    Instant closedAt,
    int rowVersion) {

  /** Loại hậu kiểm (TL-03 {@code ck_post_audit__audit_kind}). */
  public static final Set<String> KINDS = Set.of("THEO_KE_HOACH", "DOT_XUAT", "THEO_PHAN_ANH");

  /** Đối tượng hậu kiểm. */
  public static final Set<String> TARGETS = Set.of("HO_SO", "DON_VI", "NHIEM_VU", "MINH_CHUNG");

  /** Kết luận khi đóng. */
  public static final Set<String> CONCLUSIONS =
      Set.of("KHONG_SAI_LECH", "HUY_KET_QUA", "DANH_GIA_LAI");

  /** Số tệp bằng chứng tối đa của một đợt ({@code ck_post_audit__evidence}). */
  public static final int MAX_EVIDENCE = 20;

  /** Độ dài tối đa của căn cứ và phát hiện (hợp đồng {@code oversight.yaml}). */
  public static final int MAX_BASIS = 200;

  public static final int MAX_FINDINGS = 16_000;

  public PostAudit {
    Objects.requireNonNull(id, "id");
    evidenceObjectIds = evidenceObjectIds == null ? List.of() : List.copyOf(evidenceObjectIds);
  }

  public boolean open() {
    return closedAt == null;
  }

  /** Đợt đã đóng với kết luận, phát hiện đã mã hoá, yêu cầu mở lại và bằng chứng. */
  public PostAudit close(
      String conclusion,
      byte[] cipher,
      UUID dek,
      UUID reopenRequest,
      List<UUID> evidence,
      Instant at) {
    return new PostAudit(
        id,
        periodId,
        periodBucket,
        auditKind,
        targetType,
        targetId,
        basis,
        inspectorId,
        cipher,
        dek,
        conclusion,
        reopenRequest,
        evidence,
        openedAt,
        at,
        rowVersion + 1);
  }
}
