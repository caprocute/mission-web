package vn.viettel.okrkpi.oversight.infrastructure.client;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.port.ApprovalSigner;
import vn.viettel.okrkpi.shared.infrastructure.esign.EsignRequestClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Yêu cầu ký quyết định duyệt mở lại kỳ ở SVC-10: mục đích {@code PHE_DUYET_MO_LAI}, nguồn {@code
 * oversight}, mã nguồn là mã yêu cầu; SVC-10 hỏi lại tài liệu qua {@code
 * ReopenEsignSourceController}. Lỗi của SVC-10 (kể cả 401 cần xác thực bổ sung) trả nguyên.
 */
public class EsignApprovalSigner implements ApprovalSigner {

  static final String PURPOSE = "PHE_DUYET_MO_LAI";
  static final String SOURCE_MODULE = "oversight";

  private final EsignRequestClient client;

  public EsignApprovalSigner(EsignRequestClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public UUID request(
      TenantId tenant, UUID reopenRequestId, String idempotencyKey, String authorization) {
    return client.create(
        tenant, PURPOSE, SOURCE_MODULE, reopenRequestId, null, idempotencyKey, authorization);
  }
}
