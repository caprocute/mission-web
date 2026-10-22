package vn.viettel.okrkpi.appraisal.infrastructure.listener;

import java.util.Objects;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.vtokr.esign.v1.KySoDaHoanTat;

/**
 * Consumer EVT-18 {@code esign.signature.completed.v1} cho phiếu tự đánh giá (CORE-04-04, BR-09-15)
 * và kết luận thẩm định (CORE-04-05): nhận mục đích {@code TU_DANH_GIA}, {@code THAM_DINH} của
 * nguồn {@code appraisal}. Được gọi qua {@code InboxMessageProcessor} của schema appraisal nên khử
 * trùng lặp và chuyển bước cùng một giao dịch.
 */
public class SelfAssessmentSignedListener {

  /** Định danh consumer trong {@code appraisal.inbox_processed}. */
  public static final String CONSUMER = "appraisal.self-assessment-signed";

  static final String PURPOSE = "TU_DANH_GIA";
  static final String SOURCE_MODULE = "appraisal";

  static final String APPRAISAL_PURPOSE = "THAM_DINH";

  private final SelfAssessmentService service;
  private final ReviewService reviews;

  public SelfAssessmentSignedListener(SelfAssessmentService service) {
    this(service, null);
  }

  public SelfAssessmentSignedListener(SelfAssessmentService service, ReviewService reviews) {
    this.service = Objects.requireNonNull(service, "service");
    this.reviews = reviews;
  }

  public void on(KySoDaHoanTat event, InboxEnvelope<?> envelope) {
    if (!SOURCE_MODULE.equals(event.getSourceModule())) {
      return;
    }
    if (PURPOSE.equals(event.getPurpose())) {
      service.onSigned(envelope.tenantId(), event.getSignRequestId(), event.getSignedAt());
    } else if (APPRAISAL_PURPOSE.equals(event.getPurpose()) && reviews != null) {
      reviews.onAppraisalSigned(envelope.tenantId(), event.getSignRequestId(), event.getSignedAt());
    }
  }
}
