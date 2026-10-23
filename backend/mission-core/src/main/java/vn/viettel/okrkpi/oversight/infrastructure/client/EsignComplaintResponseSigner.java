package vn.viettel.okrkpi.oversight.infrastructure.client;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.port.ComplaintResponseSigner;
import vn.viettel.okrkpi.shared.infrastructure.esign.EsignRequestClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Yêu cầu ký văn bản trả lời khiếu nại ở SVC-10: mục đích {@value #PURPOSE} (suy luận, chưa có
 * trong {@code esign.sign_request.purpose} của TL-03, wiki trang 14), nguồn {@code oversight}, mã
 * nguồn là mã khiếu nại. Lỗi của SVC-10 trả nguyên.
 */
public class EsignComplaintResponseSigner implements ComplaintResponseSigner {

  public static final String PURPOSE = "TRA_LOI_KHIEU_NAI";

  private final EsignRequestClient client;

  public EsignComplaintResponseSigner(EsignRequestClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public UUID request(
      TenantId tenant, UUID complaintId, String idempotencyKey, String authorization) {
    return client.create(
        tenant,
        PURPOSE,
        EsignComplaintSigner.SOURCE_MODULE,
        complaintId,
        null,
        idempotencyKey,
        authorization);
  }
}
