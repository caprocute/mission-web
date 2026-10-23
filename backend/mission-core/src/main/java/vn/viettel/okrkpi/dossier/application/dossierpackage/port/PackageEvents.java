package vn.viettel.okrkpi.dossier.application.dossierpackage.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.PackageRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageTransferRepository.TransferRow;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Sự kiện của gói hồ sơ ghi outbox {@code dossier} cùng giao dịch (CORE-06-07, CORE-06-08). */
public interface PackageEvents {

  /**
   * Gói đã ký và nộp: ghi sổ cái ({@code sys.audit.v1}) và thông báo MSG-11-05 qua EVT-21 cho
   * {@code recipients}.
   */
  void submitted(TenantId tenant, PackageRow pkg, List<UUID> recipients, long version, UUID actor);

  /** Phiên bản gói mới khi kỳ mở lại (EVT-22): ghi sổ cái. */
  void reopened(TenantId tenant, PackageRow pkg, UUID previousId, UUID actor);

  /** Yêu cầu xuất gói thành ZIP: ghi sổ cái (xuất dữ liệu hàng loạt là sự kiện kiểm toán). */
  void exported(TenantId tenant, PackageRow pkg, UUID exportId, UUID actor);

  /** Yêu cầu gửi gói con diện TW tới {@code destination}: ghi sổ cái. */
  void transferRequested(
      TenantId tenant, PackageRow pkg, UUID batchId, String destination, int count, UUID actor);

  /** Gói con có biên nhận của bên nhận: ghi sổ cái. */
  void transferAcknowledged(TenantId tenant, PackageRow pkg, TransferRow transfer);

  /**
   * Gửi gói con chưa thành công: EVT-21 MSG-11-06 (nơi nhận, lý do, thời điểm thử lại) cho {@code
   * recipients}; {@code message} là văn bản MSG-11-06 đã điền.
   */
  void transferFailed(
      TenantId tenant, PackageRow pkg, TransferRow transfer, List<UUID> recipients, String message);
}
