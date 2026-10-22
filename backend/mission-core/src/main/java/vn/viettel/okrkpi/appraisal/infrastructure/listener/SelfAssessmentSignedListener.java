package vn.viettel.okrkpi.appraisal.infrastructure.listener;

import java.util.Objects;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.vtokr.esign.v1.KySoDaHoanTat;

/**
 * Consumer EVT-18 {@code esign.signature.completed.v1} cho phiếu tự đánh giá (CORE-04-04,
 * BR-09-15): chỉ nhận mục đích {@code TU_DANH_GIA} của nguồn {@code appraisal}. Được gọi qua {@code
 * InboxMessageProcessor} của schema appraisal nên khử trùng lặp và chuyển bước cùng một giao dịch.
 */
public class SelfAssessmentSignedListener {

  /** Định danh consumer trong {@code appraisal.inbox_processed}. */
  public static final String CONSUMER = "appraisal.self-assessment-signed";

  static final String PURPOSE = "TU_DANH_GIA";
  static final String SOURCE_MODULE = "appraisal";

  private final SelfAssessmentService service;

  public SelfAssessmentSignedListener(SelfAssessmentService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  public void on(KySoDaHoanTat event, InboxEnvelope<?> envelope) {
    if (!PURPOSE.equals(event.getPurpose()) || !SOURCE_MODULE.equals(event.getSourceModule())) {
      return;
    }
    service.onSigned(envelope.tenantId(), event.getSignRequestId(), event.getSignedAt());
  }
}
