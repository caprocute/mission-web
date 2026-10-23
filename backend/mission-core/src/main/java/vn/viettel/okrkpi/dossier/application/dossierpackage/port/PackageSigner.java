package vn.viettel.okrkpi.dossier.application.dossierpackage.port;

import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Yêu cầu ký manifest gói ở SVC-10 (mục đích {@code GOI_HO_SO}, nguồn {@code dossier}, mã nguồn là
 * mã gói) thay mặt người ký; SVC-10 hỏi lại tài liệu qua cổng nội bộ của MOD-06 (CORE-06-07).
 */
public interface PackageSigner {

  /**
   * @param provider nhà cung cấp chữ ký, {@code null} dùng mặc định của SVC-10
   * @param authorization header {@code Authorization} của người ký (SVC-10 kiểm step-up)
   * @return mã yêu cầu ký
   */
  UUID request(
      TenantId tenant,
      UUID packageId,
      String provider,
      String idempotencyKey,
      String authorization);
}
