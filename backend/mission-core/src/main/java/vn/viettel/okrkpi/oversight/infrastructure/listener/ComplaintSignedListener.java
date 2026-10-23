package vn.viettel.okrkpi.oversight.infrastructure.listener;

import java.util.Objects;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintResolutionService;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService;
import vn.viettel.okrkpi.oversight.infrastructure.client.EsignComplaintResponseSigner;
import vn.viettel.okrkpi.oversight.infrastructure.client.EsignComplaintSigner;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.vtokr.esign.v1.KySoDaHoanTat;

/**
 * Consumer EVT-18 {@code esign.signature.completed.v1} cho nội dung khiếu nại (CORE-07-02, QT-12
 * bước 2): mục đích {@code KHIEU_NAI} chuyển khiếu nại sang {@code MOI}, mục đích {@code
 * TRA_LOI_KHIEU_NAI} hoàn tất văn bản trả lời (CORE-07-04, QT-12 bước 10); chỉ nguồn {@code
 * oversight}, mã nguồn là mã khiếu nại. Gọi qua {@code InboxMessageProcessor} của schema oversight
 * nên khử trùng lặp và chuyển {@code MOI} cùng một giao dịch.
 */
public class ComplaintSignedListener {

  /** Định danh consumer trong {@code oversight.inbox_processed}. */
  public static final String CONSUMER = "oversight.complaint-signed";

  static final String SOURCE_MODULE = "oversight";

  private final ComplaintService service;
  private final ComplaintResolutionService resolution;

  public ComplaintSignedListener(ComplaintService service) {
    this(service, null);
  }

  public ComplaintSignedListener(ComplaintService service, ComplaintResolutionService resolution) {
    this.service = Objects.requireNonNull(service, "service");
    this.resolution = resolution;
  }

  public void on(KySoDaHoanTat event, InboxEnvelope<?> envelope) {
    if (!SOURCE_MODULE.equals(event.getSourceModule()) || event.getSourceRefId() == null) {
      return;
    }
    if (EsignComplaintResponseSigner.PURPOSE.equals(event.getPurpose())) {
      if (resolution != null) {
        resolution.onResponseSigned(
            envelope.tenantId(), event.getSignRequestId(), event.getSignedAt());
      }
      return;
    }
    if (!EsignComplaintSigner.PURPOSE.equals(event.getPurpose())) {
      return;
    }
    service.onSigned(
        envelope.tenantId(), event.getSourceRefId(), event.getSignRequestId(), event.getSignedAt());
  }
}
