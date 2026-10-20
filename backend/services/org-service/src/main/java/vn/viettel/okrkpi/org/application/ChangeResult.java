package vn.viettel.okrkpi.org.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.org.domain.OrgChangeType;

/**
 * Kết quả áp quyết định. {@code movedAssignments} phục vụ MSG-01-11; {@code lockedPeriodLabel} khác null khi ngày
 * hiệu lực rơi vào kỳ đã khoá (MSG-01-10, không tự tính lại kỳ).
 */
public record ChangeResult(
    UUID orderId,
    OrgChangeType changeType,
    String orderNo,
    LocalDate effectiveOn,
    String status,
    OffsetDateTime appliedAt,
    List<UUID> affectedUnitIds,
    List<UUID> createdUnitIds,
    int movedAssignments,
    String lockedPeriodLabel) {
}
