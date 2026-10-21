package vn.viettel.okrkpi.org.application.sync;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Một dòng lệch như SCR-0104 hiển thị; giá trị C2 chỉ ở dạng đã che. */
public record DiscrepancyView(UUID id, UUID personId, String personName, String maskedNationalId, String field,
    String sourceConnector, String sourceValueMasked, String currentValueMasked, String severity,
    String suggestedAction, String resolution, OffsetDateTime resolvedAt, String assigneeName, String externalId,
    String matchedBy, LocalDate effectiveFrom, UUID syncRunId) {
}
