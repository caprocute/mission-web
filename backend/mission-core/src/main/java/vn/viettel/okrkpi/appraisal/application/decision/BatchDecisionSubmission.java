package vn.viettel.okrkpi.appraisal.application.decision;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.Command;
import vn.viettel.okrkpi.appraisal.application.decision.BatchDecisionService.Prepared;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SignRequests;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lệnh "Quyết định và ký lô" (SCR-0904) qua hai giao dịch: ghi lô {@code NHAP} cùng các dòng mức,
 * rồi mở yêu cầu ký lô ở SVC-10 (khoá idempotency theo mã lô nên gửi lại không tạo yêu cầu ký mới),
 * cuối cùng gắn mã yêu cầu ký và chuyển {@code CHO_KY}. Mở yêu cầu ký lỗi thì lô {@code NHAP} được
 * giữ; gửi lại cùng số quyết định, cùng danh sách sẽ tiếp tục lô đó khi dịch vụ ký phục hồi.
 */
public class BatchDecisionSubmission {

  static final String SIGN_FAILED =
      "Ký số chưa thành công. Lô quyết định đã được ghi lại, đồng chí gửi lại để tiếp tục ký.";

  private final BatchDecisionService service;
  private final SignRequests signs;

  public BatchDecisionSubmission(BatchDecisionService service, SignRequests signs) {
    this.service = Objects.requireNonNull(service, "service");
    this.signs = Objects.requireNonNull(signs, "signs");
  }

  /**
   * @return mã lô (cũng là mã tác vụ theo dõi)
   */
  public UUID decide(
      TenantId tenant,
      UUID actor,
      Command command,
      Scope scope,
      String provider,
      String authorization) {
    Prepared prepared = service.prepare(tenant, actor, command, scope);
    if (prepared.signRequestId() != null) {
      return prepared.batchId();
    }
    UUID signRequestId;
    try {
      signRequestId =
          signs.requestDecision(
              tenant,
              prepared.batchId(),
              provider,
              "batch-decision:" + prepared.batchId(),
              authorization);
    } catch (BusinessException e) {
      throw e;
    } catch (SignRequests.Relayed e) {
      throw (RuntimeException) e.getCause();
    } catch (RuntimeException e) {
      throw new BusinessException("ESIGN-422-PROVIDER-UNAVAILABLE", SIGN_FAILED);
    }
    service.attachSignRequest(tenant, prepared, signRequestId, actor);
    return prepared.batchId();
  }
}
