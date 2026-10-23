package vn.viettel.okrkpi.dossier.application.transfer.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hồ sơ cá nhân đã ký và kết luận kiểm điểm của một cán bộ ({@code dossier_package}, {@code
 * review_comment}).
 */
public interface DossierHistory {

  /** Gói hồ sơ cá nhân đã ký ({@code DA_KY}, {@code DA_NOP}, {@code DA_LUU_TRU}). */
  record SignedPackage(
      UUID periodId,
      String code,
      String packageKind,
      int versionNo,
      String status,
      byte[] manifestHash,
      byte[] packageSha256,
      Instant submittedAt) {}

  /** Kết luận kiểm điểm ({@code comment_kind = KET_LUAN}), nội dung còn ở dạng mã. */
  record Conclusion(
      UUID id,
      UUID periodId,
      String kind,
      String phase,
      byte[] cipher,
      LocalDate remediationDue,
      Instant createdAt) {}

  List<SignedPackage> signedPackages(TenantId tenant, UUID personId);

  List<Conclusion> conclusions(TenantId tenant, UUID personId);
}
