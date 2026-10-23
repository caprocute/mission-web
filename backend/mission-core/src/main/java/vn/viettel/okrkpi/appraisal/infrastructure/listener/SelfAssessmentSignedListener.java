package vn.viettel.okrkpi.appraisal.infrastructure.listener;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import vn.viettel.okrkpi.appraisal.application.decision.DecisionSigningService;
import vn.viettel.okrkpi.appraisal.application.prospect.ProspectBoardService;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.vtokr.esign.v1.KySoDaHoanTat;
import vn.vtokr.esign.v1.SignedItem;

/**
 * Consumer EVT-18 {@code esign.signature.completed.v1} cho phiếu tự đánh giá (CORE-04-04,
 * BR-09-15), kết luận thẩm định (CORE-04-05) và lô quyết định (CORE-04-08): nhận mục đích {@code
 * TU_DANH_GIA}, {@code THAM_DINH}, {@code QUYET_DINH} (nguồn là mã lô) của nguồn {@code appraisal}.
 * Được gọi qua {@code InboxMessageProcessor} của schema appraisal nên khử trùng lặp và chuyển bước
 * cùng một giao dịch.
 */
public class SelfAssessmentSignedListener {

  /** Định danh consumer trong {@code appraisal.inbox_processed}. */
  public static final String CONSUMER = "appraisal.self-assessment-signed";

  static final String PURPOSE = "TU_DANH_GIA";
  static final String SOURCE_MODULE = "appraisal";

  static final String APPRAISAL_PURPOSE = "THAM_DINH";
  static final String DECISION_PURPOSE = "QUYET_DINH";

  private final SelfAssessmentService service;
  private final ReviewService reviews;
  private final DecisionSigningService decisions;
  private final ProspectBoardService prospects;

  public SelfAssessmentSignedListener(SelfAssessmentService service) {
    this(service, null);
  }

  public SelfAssessmentSignedListener(SelfAssessmentService service, ReviewService reviews) {
    this(service, reviews, null);
  }

  public SelfAssessmentSignedListener(
      SelfAssessmentService service, ReviewService reviews, DecisionSigningService decisions) {
    this(service, reviews, decisions, null);
  }

  /**
   * Như trên, kèm danh sách phân nhóm triển vọng (CORE-04-12): yêu cầu ký nào đã ghi trên một danh
   * sách thì thuộc danh sách đó, không phụ thuộc mục đích ký.
   */
  public SelfAssessmentSignedListener(
      SelfAssessmentService service,
      ReviewService reviews,
      DecisionSigningService decisions,
      ProspectBoardService prospects) {
    this.service = Objects.requireNonNull(service, "service");
    this.reviews = reviews;
    this.decisions = decisions;
    this.prospects = prospects;
  }

  public void on(KySoDaHoanTat event, InboxEnvelope<?> envelope) {
    if (!SOURCE_MODULE.equals(event.getSourceModule())) {
      return;
    }
    if (prospects != null
        && prospects.onSigned(envelope.tenantId(), event.getSignRequestId(), event.getSignedAt())) {
      return;
    }
    if (PURPOSE.equals(event.getPurpose())) {
      service.onSigned(envelope.tenantId(), event.getSignRequestId(), event.getSignedAt());
    } else if (APPRAISAL_PURPOSE.equals(event.getPurpose()) && reviews != null) {
      reviews.onAppraisalSigned(envelope.tenantId(), event.getSignRequestId(), event.getSignedAt());
    } else if (DECISION_PURPOSE.equals(event.getPurpose()) && decisions != null) {
      decisions.onSigned(
          envelope.tenantId(),
          event.getSourceRefId(),
          event.getSignRequestId(),
          hashes(event),
          event.getSignedAt());
    }
  }

  /** Băm tài liệu đã ký (hex chữ thường) của các mục trong EVT-18. */
  static Set<String> hashes(KySoDaHoanTat event) {
    Set<String> out = new HashSet<>();
    if (event.getItems() != null) {
      for (SignedItem item : event.getItems()) {
        ByteBuffer b = item.getDocumentSha256().duplicate();
        byte[] bytes = new byte[b.remaining()];
        b.get(bytes);
        out.add(HexFormat.of().formatHex(bytes));
      }
    }
    return out;
  }
}
