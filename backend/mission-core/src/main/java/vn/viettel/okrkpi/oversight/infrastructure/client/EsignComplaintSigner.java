package vn.viettel.okrkpi.oversight.infrastructure.client;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSigner;
import vn.viettel.okrkpi.shared.infrastructure.esign.EsignRequestClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Yêu cầu ký nội dung khiếu nại ở SVC-10: mục đích {@value #PURPOSE}, nguồn {@code oversight}, mã
 * nguồn là mã khiếu nại; SVC-10 hỏi lại tài liệu qua {@code ComplaintEsignSourceController}. Mục
 * đích chưa có trong {@code esign.sign_request.purpose} của TL-03 (wiki trang 14). Lỗi của SVC-10
 * (kể cả 401 cần xác thực bổ sung) trả nguyên.
 */
public class EsignComplaintSigner implements ComplaintSigner {

  public static final String PURPOSE = "KHIEU_NAI";
  static final String SOURCE_MODULE = "oversight";

  private final EsignRequestClient client;

  public EsignComplaintSigner(EsignRequestClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public UUID request(
      TenantId tenant, UUID complaintId, String idempotencyKey, String authorization) {
    return client.create(
        tenant, PURPOSE, SOURCE_MODULE, complaintId, null, idempotencyKey, authorization);
  }
}
