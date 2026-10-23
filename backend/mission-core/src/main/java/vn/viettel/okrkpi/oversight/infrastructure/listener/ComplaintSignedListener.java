package vn.viettel.okrkpi.oversight.infrastructure.listener;

import java.util.Objects;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService;
import vn.viettel.okrkpi.oversight.infrastructure.client.EsignComplaintSigner;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.vtokr.esign.v1.KySoDaHoanTat;

/**
 * Consumer EVT-18 {@code esign.signature.completed.v1} cho nội dung khiếu nại (CORE-07-02, QT-12
 * bước 2): chỉ nhận mục đích {@code KHIEU_NAI} của nguồn {@code oversight}; mã nguồn là mã khiếu
 * nại. Gọi qua {@code InboxMessageProcessor} của schema oversight nên khử trùng lặp và chuyển
 * {@code MOI} cùng một giao dịch.
 */
public class ComplaintSignedListener {

  /** Định danh consumer trong {@code oversight.inbox_processed}. */
  public static final String CONSUMER = "oversight.complaint-signed";

  static final String SOURCE_MODULE = "oversight";

  private final ComplaintService service;

  public ComplaintSignedListener(ComplaintService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  public void on(KySoDaHoanTat event, InboxEnvelope<?> envelope) {
    if (!EsignComplaintSigner.PURPOSE.equals(event.getPurpose())
        || !SOURCE_MODULE.equals(event.getSourceModule())
        || event.getSourceRefId() == null) {
      return;
    }
    service.onSigned(
        envelope.tenantId(), event.getSourceRefId(), event.getSignRequestId(), event.getSignedAt());
  }
}
