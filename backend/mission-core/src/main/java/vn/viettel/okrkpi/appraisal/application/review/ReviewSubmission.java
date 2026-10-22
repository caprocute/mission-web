package vn.viettel.okrkpi.appraisal.application.review;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService.AppraisalDraft;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService.Prepared;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SignRequests;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lệnh "Ký số kết quả thẩm định" (SCR-0902) qua hai giao dịch như phiếu tự đánh giá: ghi kết luận
 * chờ ký, rồi gọi SVC-10 (SVC-10 hỏi lại tài liệu qua cổng nội bộ của MOD-04), cuối cùng gắn mã yêu
 * cầu ký. Mở yêu cầu ký lỗi thì kết luận đã ghi được giữ, lần gửi lại tạo dòng mức mới.
 */
public class ReviewSubmission {

  static final String SIGN_FAILED =
      "Ký số chưa thành công. Kết luận đã nhập được giữ nguyên, đồng chí vui lòng thử lại.";

  private final ReviewService service;
  private final SignRequests signs;

  public ReviewSubmission(ReviewService service, SignRequests signs) {
    this.service = Objects.requireNonNull(service, "service");
    this.signs = Objects.requireNonNull(signs, "signs");
  }

  /**
   * @return mã yêu cầu ký (cũng là mã tác vụ theo dõi)
   */
  public UUID appraise(
      TenantId tenant,
      UUID profileId,
      long expectedVersion,
      UUID actor,
      AppraisalDraft draft,
      String provider,
      String idempotencyKey,
      String authorization) {
    Prepared prepared = service.prepareAppraisal(tenant, profileId, expectedVersion, actor, draft);
    if (prepared.signRequestId() != null) {
      return prepared.signRequestId();
    }
    UUID signRequestId;
    try {
      signRequestId =
          signs.requestAppraisal(tenant, profileId, provider, idempotencyKey, authorization);
    } catch (BusinessException e) {
      throw e;
    } catch (SignRequests.Relayed e) {
      throw (RuntimeException) e.getCause();
    } catch (RuntimeException e) {
      throw new BusinessException("ESIGN-422-PROVIDER-UNAVAILABLE", SIGN_FAILED);
    }
    service.attachSignRequest(tenant, prepared, signRequestId);
    return signRequestId;
  }
}
