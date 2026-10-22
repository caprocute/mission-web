package vn.viettel.okrkpi.oversight.infrastructure.listener;

import java.util.Objects;
import vn.viettel.okrkpi.oversight.application.reopen.ReopenApprovalService;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.vtokr.esign.v1.KySoDaHoanTat;

/**
 * Consumer EVT-18 {@code esign.signature.completed.v1} cho quyết định duyệt mở lại kỳ (CORE-07-08,
 * BR-13-04): chỉ nhận mục đích {@code PHE_DUYET_MO_LAI} của nguồn {@code oversight}; mã nguồn là mã
 * yêu cầu. Được gọi qua {@code InboxMessageProcessor} của schema oversight nên khử trùng lặp và
 * chuyển bước cùng một giao dịch.
 */
public class ReopenSignedListener {

  /** Định danh consumer trong {@code oversight.inbox_processed}. */
  public static final String CONSUMER = "oversight.reopen-signed";

  static final String PURPOSE = "PHE_DUYET_MO_LAI";
  static final String SOURCE_MODULE = "oversight";

  private final ReopenApprovalService service;

  public ReopenSignedListener(ReopenApprovalService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  public void on(KySoDaHoanTat event, InboxEnvelope<?> envelope) {
    if (!PURPOSE.equals(event.getPurpose())
        || !SOURCE_MODULE.equals(event.getSourceModule())
        || event.getSourceRefId() == null) {
      return;
    }
    service.onSigned(
        envelope.tenantId(), event.getSourceRefId(), event.getSignRequestId(), event.getSignedAt());
  }
}
