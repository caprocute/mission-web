package vn.viettel.okrkpi.dossier.application.dossierpackage.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.PackageRow;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Sự kiện của gói hồ sơ ghi outbox {@code dossier} cùng giao dịch (CORE-06-07). */
public interface PackageEvents {

  /**
   * Gói đã ký và nộp: ghi sổ cái ({@code sys.audit.v1}) và thông báo MSG-11-05 qua EVT-21 cho
   * {@code recipients}.
   */
  void submitted(TenantId tenant, PackageRow pkg, List<UUID> recipients, long version, UUID actor);

  /** Phiên bản gói mới khi kỳ mở lại (EVT-22): ghi sổ cái. */
  void reopened(TenantId tenant, PackageRow pkg, UUID previousId, UUID actor);
}
