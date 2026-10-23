package vn.viettel.okrkpi.oversight.domain.complaint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phần xử lý của khiếu nại (CORE-07-03, migration oversight V3, suy luận): người tiếp nhận, hạn và
 * thời điểm tiếp nhận, lý do và thời điểm đóng khi không thụ lý hoặc rút.
 */
public record ComplaintHandling(
    UUID handlerId,
    LocalDate acceptDeadline,
    Instant acceptedAt,
    ComplaintCloseReason closeReasonCode,
    String closeReason,
    Instant closedAt) {

  public static final ComplaintHandling NONE =
      new ComplaintHandling(null, null, null, null, null, null);

  ComplaintHandling withAcceptDeadline(LocalDate deadline) {
    return new ComplaintHandling(
        handlerId, deadline, acceptedAt, closeReasonCode, closeReason, closedAt);
  }

  ComplaintHandling withHandler(UUID handler, Instant at) {
    return new ComplaintHandling(
        handler, acceptDeadline, at, closeReasonCode, closeReason, closedAt);
  }

  ComplaintHandling withClose(ComplaintCloseReason code, String reason, Instant at) {
    return new ComplaintHandling(handlerId, acceptDeadline, acceptedAt, code, reason, at);
  }
}
