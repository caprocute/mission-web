package vn.viettel.okrkpi.org.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Một dòng {@code org.org_change_order}; {@code payload} không chứa C2. */
public record ChangeOrderRecord(
    UUID id,
    String orderNo,
    LocalDate orderDate,
    String kind,
    LocalDate effectiveOn,
    String signedDocRef,
    Map<String, Object> payload,
    String status,
    OffsetDateTime appliedAt) {
}
