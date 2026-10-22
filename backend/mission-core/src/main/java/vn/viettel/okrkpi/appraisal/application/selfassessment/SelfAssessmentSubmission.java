package vn.viettel.okrkpi.appraisal.application.selfassessment;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.selfassessment.SelfAssessmentService.Prepared;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SignRequests;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lệnh "Ký số và nộp" (SCR-0901) qua hai giao dịch: chốt phiếu chờ ký, rồi mới gọi SVC-10 vì SVC-10
 * hỏi lại tài liệu cần ký qua cổng nội bộ của MOD-04 trong lúc tạo yêu cầu; cuối cùng gắn mã yêu
 * cầu ký vào phiếu. Mở yêu cầu ký lỗi thì bỏ trạng thái chờ ký, dữ liệu đã nhập giữ nguyên
 * (MSG-09-05).
 */
public class SelfAssessmentSubmission {

  /** Thông điệp MSG-09-05 khi chưa mở được yêu cầu ký. */
  static final String SIGN_FAILED =
      "Ký số chưa thành công. Dữ liệu đã nhập được giữ nguyên, đồng chí vui lòng thử lại.";

  private final SelfAssessmentService service;
  private final SignRequests signs;

  public SelfAssessmentSubmission(SelfAssessmentService service, SignRequests signs) {
    this.service = Objects.requireNonNull(service, "service");
    this.signs = Objects.requireNonNull(signs, "signs");
  }

  /**
   * @param provider nhà cung cấp chữ ký, {@code null} dùng mặc định của SVC-10
   * @param clientHash băm phiếu client đã xem, có thể {@code null}
   * @param authorization header {@code Authorization} của người ký, chuyển tiếp cho SVC-10
   * @return mã yêu cầu ký (cũng là mã tác vụ theo dõi)
   */
  public UUID submit(
      TenantId tenant,
      UUID profileId,
      long expectedVersion,
      UUID actor,
      String provider,
      byte[] clientHash,
      String idempotencyKey,
      String authorization) {
    Prepared prepared =
        service.prepareSubmit(tenant, profileId, expectedVersion, actor, clientHash);
    if (prepared.signRequestId() != null) {
      return prepared.signRequestId();
    }
    UUID signRequestId;
    try {
      signRequestId =
          signs.requestSelfAssessment(tenant, profileId, provider, idempotencyKey, authorization);
    } catch (BusinessException e) {
      service.abortSubmit(tenant, prepared);
      throw e;
    } catch (SignRequests.Relayed e) {
      service.abortSubmit(tenant, prepared);
      throw (RuntimeException) e.getCause();
    } catch (RuntimeException e) {
      service.abortSubmit(tenant, prepared);
      throw new BusinessException("ESIGN-422-PROVIDER-UNAVAILABLE", SIGN_FAILED);
    }
    service.attachSignRequest(tenant, prepared, signRequestId);
    return signRequestId;
  }
}
