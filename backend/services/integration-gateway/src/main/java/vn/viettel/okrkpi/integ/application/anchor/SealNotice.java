package vn.viettel.okrkpi.integ.application.anchor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Phần EVT-19 {@code ledger.seal.created.v1} mà luồng neo cần: mã sự kiện, loại và cửa sổ niêm
 * phong. Gốc Merkle mức tenant trong sự kiện không gửi ra ngoài; bản neo dùng gốc cấp cell do
 * ledger-service dựng.
 */
public record SealNotice(
    UUID eventId, UUID sealId, String sealKind, Instant windowStart, Instant windowEnd) {

  public SealNotice {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(sealId, "sealId");
    Objects.requireNonNull(sealKind, "sealKind");
    Objects.requireNonNull(windowEnd, "windowEnd");
  }
}
