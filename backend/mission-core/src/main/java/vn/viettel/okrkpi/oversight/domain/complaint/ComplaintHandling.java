package vn.viettel.okrkpi.oversight.domain.complaint;

import java.time.Instant;
import java.util.UUID;

/**
 * Phần xử lý của khiếu nại đã gửi (CORE-07-03, CORE-07-04): người xử lý đã tiếp nhận, lý do đóng
 * khi không thụ lý hoặc rút, bản nháp kết luận, yêu cầu ký văn bản trả lời và yêu cầu mở lại kỳ.
 */
public record ComplaintHandling(
    UUID handlerPersonId,
    Instant acceptedAt,
    CloseReason closeReason,
    String closeNote,
    Instant closedAt,
    ComplaintConclusion conclusion,
    UUID responseSignRequestId,
    Instant respondedAt,
    UUID reopenRequestId) {

  /** Khiếu nại chưa được xử lý. */
  public static final ComplaintHandling NONE =
      new ComplaintHandling(null, null, null, null, null, null, null, null, null);

  public ComplaintHandling accepted(UUID handler, Instant at) {
    return new ComplaintHandling(
        handler,
        at,
        closeReason,
        closeNote,
        closedAt,
        conclusion,
        responseSignRequestId,
        respondedAt,
        reopenRequestId);
  }

  public ComplaintHandling closed(CloseReason reason, String note, Instant at) {
    return new ComplaintHandling(
        handlerPersonId,
        acceptedAt,
        reason,
        note,
        at,
        conclusion,
        responseSignRequestId,
        respondedAt,
        reopenRequestId);
  }

  public ComplaintHandling concluded(ComplaintConclusion c, UUID reopenRequest) {
    return new ComplaintHandling(
        handlerPersonId,
        acceptedAt,
        closeReason,
        closeNote,
        closedAt,
        c,
        responseSignRequestId,
        respondedAt,
        reopenRequest);
  }

  public ComplaintHandling signing(UUID signRequest) {
    return new ComplaintHandling(
        handlerPersonId,
        acceptedAt,
        closeReason,
        closeNote,
        closedAt,
        conclusion,
        signRequest,
        respondedAt,
        reopenRequestId);
  }

  public ComplaintHandling responded(Instant at) {
    return new ComplaintHandling(
        handlerPersonId,
        acceptedAt,
        closeReason,
        closeNote,
        closedAt,
        conclusion,
        responseSignRequestId,
        at,
        reopenRequestId);
  }
}
