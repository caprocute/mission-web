package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Đề nghị huỷ và bù trừ toàn bộ saga chốt kỳ ({@code config.period_lock_order.cancel_*}, BR-08-09):
 * người đề nghị (AC-02) và người duyệt thứ hai (AC-09) khác nhau.
 */
public record LockCancelRequest(
    UUID requestedBy, Instant requestedAt, String reason, UUID approvedBy, Instant approvedAt) {

  public LockCancelRequest {
    Objects.requireNonNull(requestedBy, "requestedBy");
  }

  public boolean approved() {
    return approvedBy != null;
  }

  LockCancelRequest approve(UUID approver, Instant at) {
    return new LockCancelRequest(requestedBy, requestedAt, reason, approver, at);
  }
}
