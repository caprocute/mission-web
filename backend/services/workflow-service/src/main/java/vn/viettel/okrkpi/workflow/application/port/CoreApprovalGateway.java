package vn.viettel.okrkpi.workflow.application.port;

import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.Decision;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;

/**
 * Cổng gọi API công bố của lõi để áp kết quả luồng duyệt (TL-01 G2: "tầng 2 gọi API công bố của
 * lõi ... bằng external worker có idempotency key, không gọi trong giao dịch engine").
 */
public interface CoreApprovalGateway {

  /** Gọi lõi; không ném lỗi mạng mà trả {@link Outcome#RETRY}. */
  Outcome apply(Request request);

  /** Kết quả gọi lõi. */
  enum Outcome {
    /** Lõi đã áp (kể cả trả lại phản hồi lần đầu theo cùng Idempotency-Key). */
    APPLIED,
    /** Lỗi tạm thời (mạng, 5xx, 429): thử lại sau. */
    RETRY,
    /** Lõi từ chối dứt khoát (4xx): cần người xử lý, không thử lại. */
    REJECTED
  }

  /**
   * Lời gọi áp kết quả.
   *
   * @param tenantId tenant
   * @param template mẫu luồng
   * @param decision quyết định cần áp
   * @param businessId id đối tượng
   * @param objectVersion phiên bản đối tượng lúc trình, gửi ở {@code If-Match}
   * @param idempotencyKey khoá cố định theo (tiến trình, bước) để lần thử lại dùng lại khoá cũ
   * @param deciderId người quyết định, có thể {@code null}
   * @param comment ý kiến, có thể {@code null}
   * @param planVersionNo số phiên bản kế hoạch (mẫu kế hoạch)
   * @param signMethod phương thức ký (mẫu kế hoạch)
   * @param taskId nhiệm vụ (mẫu đề nghị)
   * @param cause nguyên nhân (mẫu đề nghị)
   */
  record Request(
      UUID tenantId,
      ApprovalTemplate template,
      Decision decision,
      UUID businessId,
      long objectVersion,
      UUID idempotencyKey,
      UUID deciderId,
      String comment,
      Integer planVersionNo,
      String signMethod,
      UUID taskId,
      String cause) {

    public Request {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(template, "template");
      Objects.requireNonNull(decision, "decision");
      Objects.requireNonNull(businessId, "businessId");
      Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }
  }
}
