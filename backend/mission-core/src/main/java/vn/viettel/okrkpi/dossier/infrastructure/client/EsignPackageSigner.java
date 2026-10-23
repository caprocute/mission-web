package vn.viettel.okrkpi.dossier.infrastructure.client;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageSigner;
import vn.viettel.okrkpi.shared.infrastructure.esign.EsignRequestClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Yêu cầu ký manifest gói ở SVC-10: mục đích {@value #PURPOSE}, nguồn {@value #SOURCE_MODULE}, mã
 * nguồn là mã gói; SVC-10 hỏi lại tài liệu qua {@code DossierEsignSourceController}. Lỗi của SVC-10
 * (401 cần xác thực bổ sung, {@code ESIGN-422-CERTIFICATE-INVALID}) trả nguyên.
 */
public class EsignPackageSigner implements PackageSigner {

  public static final String PURPOSE = "GOI_HO_SO";
  public static final String SOURCE_MODULE = "dossier";

  private final EsignRequestClient client;

  public EsignPackageSigner(EsignRequestClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public UUID request(
      TenantId tenant,
      UUID packageId,
      String provider,
      String idempotencyKey,
      String authorization) {
    return client.create(
        tenant, PURPOSE, SOURCE_MODULE, packageId, provider, idempotencyKey, authorization);
  }
}
