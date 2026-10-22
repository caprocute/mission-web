package vn.viettel.okrkpi.oversight.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Điều kiện tra cứu nhật ký truy cập C2 ({@code searchAccessLogs}, CORE-07-12). Trường {@code null}
 * là không lọc; {@code from} bao gồm, {@code to} không bao gồm.
 */
public record AccessLogQuery(
    UUID actorId,
    String objectType,
    String objectId,
    boolean othersOnly,
    Instant from,
    Instant to) {

  public AccessLogQuery {
    if (from != null && to != null && !from.isBefore(to)) {
      throw new IllegalArgumentException("Khoảng thời gian tra cứu không hợp lệ");
    }
  }
}
